package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackStartupVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val request = Request.Builder().url("https://fixture.invalid/metadata").build()
            fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            fun response(req: Request, status: Int = 200) = Response.Builder().request(req)
                .protocol(Protocol.HTTP_1_1).code(status).message("fixture").body("ok".toResponseBody()).build()
            val count = AtomicInteger()
            val fast = OkHttpClient.Builder().addInterceptor { chain -> count.incrementAndGet(); response(chain.request()) }.build()
            HedgedPlaybackRequest.execute(fast, request, deadline(), 150).use { check(it.code == 200) }
            Thread.sleep(250)
            check(count.get() == 1) { "fast request duplicated" }
            val races = AtomicInteger()
            val slow = OkHttpClient.Builder().addInterceptor { chain ->
                if (races.incrementAndGet() == 1) {
                    while (!chain.call().isCanceled()) Thread.sleep(10)
                    throw IOException("fixture cancelled")
                }
                response(chain.request())
            }.build()
            val start = SystemClock.elapsedRealtime()
            HedgedPlaybackRequest.execute(slow, request, deadline(), 150).use { check(it.code == 200) }
            val elapsed = SystemClock.elapsedRealtime() - start
            check(races.get() == 2 && elapsed in 100..2000) { "slow request was not rescued: $elapsed" }
            val deniedCount = AtomicInteger()
            val denied = OkHttpClient.Builder().addInterceptor { chain -> deniedCount.incrementAndGet(); response(chain.request(), 403) }.build()
            HedgedPlaybackRequest.execute(denied, request, deadline(), 150).use { check(it.code == 403) }
            Thread.sleep(250)
            check(deniedCount.get() == 1) { "denial was retried" }
            result.putString("result", "PASS fast single request; slow request raced and cancelled; HTTP denial preserved; race ${elapsed}ms")
            finish(-1, result)
        } catch (t: Throwable) { result.putString("failure", t.stackTraceToString()); finish(0, result) }
    }
}
