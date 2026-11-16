package com.shixi.client

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Race one fresh connection only when an idempotent playback metadata request is slow. */
internal object HedgedPlaybackRequest {
    fun execute(client: OkHttpClient, request: Request, deadline: Long, delayMs: Long = 1_500): Response {
        val gate = java.lang.Object()
        var winner: Response? = null
        var winningCall: Call? = null
        var lastFailure: IOException? = null
        var completed = 0
        var abandoned = false
        val calls = mutableListOf<Call>()
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) = synchronized(gate) {
                lastFailure = e
                completed++
                gate.notifyAll()
            }
            override fun onResponse(call: Call, response: Response) = synchronized(gate) {
                completed++
                if (winner == null && !abandoned) { winner = response; winningCall = call } else response.close()
                gate.notifyAll()
            }
        }
        fun start(transport: OkHttpClient) {
            val call = transport.newCall(request)
            call.timeout().deadlineNanoTime(deadline)
            calls.add(call)
            call.enqueue(callback)
        }
        try {
            synchronized(gate) {
                start(client)
                val hedgeAt = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs))
                while (winner == null && completed == 0 && System.nanoTime() < hedgeAt) {
                    val left = hedgeAt - System.nanoTime()
                    if (left > 0) TimeUnit.NANOSECONDS.timedWait(gate, left)
                }
                if (winner == null && System.nanoTime() < deadline) {
                    StartupTiming.mark("metadata_retry_race")
                    // Do not reuse the stalled pooled connection for the second attempt.
                    start(client.newBuilder().connectionPool(okhttp3.ConnectionPool()).build())
                }
                while (winner == null && completed < calls.size && System.nanoTime() < deadline) {
                    val left = deadline - System.nanoTime()
                    if (left > 0) TimeUnit.NANOSECONDS.timedWait(gate, left)
                }
                return winner ?: throw lastFailure ?: InterruptedIOException("播放请求超时")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("播放请求已中断").apply { initCause(e) }
        } finally {
            synchronized(gate) {
                abandoned = true
                calls.forEach { call -> if (call !== winningCall) call.cancel() }
            }
        }
    }
}
