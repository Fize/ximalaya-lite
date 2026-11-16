package com.shixi.client

import android.content.*
import android.os.*
import com.shixi.protocol.ContentServiceException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A native decoder crash stays in the existing private service process; a new call can rebind. */
internal object PaidMediaDecoder {
    fun decode(context: Context, file: String, ep: String, timeoutMs: Long = 10_000): Pair<String, String> {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(timeoutMs in 100..10_000 && file.length in 4..8192 && ep.length in 4..8192)
        val app = context.applicationContext; val done = CountDownLatch(1); val terminal = AtomicBoolean(false)
        val value = AtomicReference<Pair<String, String>?>(null)
        fun finish(result: Pair<String, String>?) { if (terminal.compareAndSet(false, true)) { value.set(result); done.countDown() } }
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != 4 || message.sendingUid != Process.myUid()) return
                val path = message.data.getString("path"); val decoded = message.data.getString("ep")
                finish(if (path != null && decoded != null && path.length in 1..4096 && decoded.length in 1..4096) path to decoded else null)
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (terminal.get()) return
                try { Messenger(binder).send(Message.obtain(null, 3).apply { replyTo = reply; data = Bundle().apply { putString("file", file); putString("ep", ep) } }) }
                catch (_: RemoteException) { finish(null) }
            }
            override fun onServiceDisconnected(name: ComponentName) { finish(null) }
            override fun onBindingDied(name: ComponentName) { finish(null) }
            override fun onNullBinding(name: ComponentName) { finish(null) }
        }
        var bound = false
        try {
            bound = app.bindService(Intent(app, HistorySignerService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound || !done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw ContentServiceException("播放地址解码服务暂不可用，请重试（PLAY_DECODE_TIMEOUT）")
            return value.get() ?: throw ContentServiceException("播放地址解码服务中断，请重试（PLAY_DECODE_SERVICE）")
        } finally { terminal.set(true); if (bound) app.unbindService(connection) }
    }
}
