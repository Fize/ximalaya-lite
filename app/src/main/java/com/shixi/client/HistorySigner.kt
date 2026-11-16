package com.shixi.client

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import com.ximalaya.ting.android.encryptservice.EncryptUtil
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Native signer is loaded only in the private secondary process, never beside the player. */
class HistorySignerService : Service() {
    private val messenger by lazy { Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            if (message.what == 3) {
                val reply = message.replyTo ?: return
                val response = Message.obtain(null, 4)
                try {
                    val file = message.data.getString("file") ?: error("file")
                    val ep = message.data.getString("ep") ?: error("ep")
                    require(file.length in 4..8192 && file.matches(Regex("[A-Za-z0-9+/=\\r\\n]+")))
                    require(ep.length in 4..8192 && ep.all { it.code in 33..126 })
                    val decoded = EncryptUtil().paidMedia(applicationContext, file, ep)
                    require(decoded.size == 2 && decoded.all { it.length in 1..4096 })
                    response.data = Bundle().apply { putString("path", decoded[0]); putString("ep", decoded[1]) }
                } catch (_: Exception) { response.data = Bundle.EMPTY }
                catch (_: LinkageError) { response.data = Bundle.EMPTY }
                try { reply.send(response) } catch (_: RemoteException) { }
                return
            }
            if (message.what != 1) return
            val reply = message.replyTo ?: return
            val response = Message.obtain(null, 2)
            try {
                val input = message.data.getString("fields") ?: error("missing fields")
                require(input.length <= 65_536)
                val json = JSONObject(input); require(json.length() in 1..16)
                val fields = json.keys().asSequence().associateWith { key ->
                    require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")))
                    json.getString(key).also { require(it.length <= 32_768) }
                }
                val value = EncryptUtil().historySignature(applicationContext, fields)
                require(!value.isNullOrBlank() && value.length <= 4096)
                response.data = Bundle().apply { putString("signature", value) }
            } catch (_: Exception) { response.data = Bundle.EMPTY }
            catch (_: LinkageError) { response.data = Bundle.EMPTY }
            try { reply.send(response) } catch (_: RemoteException) { }
        }
    }) }
    override fun onBind(intent: Intent): IBinder = messenger.binder
}

object HistorySigner {
    fun sign(context: Context, fields: Map<String, String>, timeoutMs: Long = 10_000): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "signing must not block UI" }
        require(timeoutMs in 100..10_000)
        val app = context.applicationContext; val done = CountDownLatch(1)
        val terminal = AtomicBoolean(false); val signature = AtomicReference<String?>(null)
        fun finish(value: String?) { if (terminal.compareAndSet(false, true)) { signature.set(value); done.countDown() } }
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what == 2 && message.sendingUid == Process.myUid()) finish(message.data.getString("signature"))
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (terminal.get()) return
                try {
                    Messenger(service).send(Message.obtain(null, 1).apply {
                        replyTo = reply; data = Bundle().apply { putString("fields", JSONObject(fields).toString()) }
                    })
                } catch (_: RemoteException) { finish(null) }
            }
            override fun onServiceDisconnected(name: ComponentName) { finish(null) }
            override fun onBindingDied(name: ComponentName) { finish(null) }
            override fun onNullBinding(name: ComponentName) { finish(null) }
        }
        var bound = false
        try {
            bound = app.bindService(Intent(app, HistorySignerService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound || !done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw CloudFailure(message = "历史签名服务暂不可用，待同步记录已保留，可手动重试")
            return signature.get()?.takeIf { it.isNotBlank() && it.length <= 4096 }
                ?: throw CloudFailure(message = "历史签名服务中断，待同步记录已保留，可手动重试")
        } finally {
            terminal.set(true)
            if (bound) app.unbindService(connection)
        }
    }
}
