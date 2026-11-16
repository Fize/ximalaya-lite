package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.shixi.protocol.Item
import java.util.concurrent.TimeUnit

class SkipHeadTailVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle(); var controller: MediaController? = null
        val store = SkipHeadTailStore(targetContext)
        val previous = store.get(3544)
        fun checkEventually(name: String, seconds: Int = 35, test: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
            while (System.nanoTime() < end) { var ok = false; runOnMainSync { ok = test() }; if (ok) return; Thread.sleep(100) }
            error("超时：$name")
        }
        try {
            val policy = SkipHeadTailPolicy()
            check(policy.target(0, 100_000, SkipHeadTail(10, 5)) == 10_000L)
            check(policy.target(0, 100_000, SkipHeadTail(10, 5)) == null)
            check(policy.target(94_999, 100_000, SkipHeadTail(10, 5)) == null)
            check(policy.target(95_000, 100_000, SkipHeadTail(10, 5)) == 100_000L)
            check(policy.target(95_000, 100_000, SkipHeadTail(10, 5)) == null)
            policy.reset(); check(policy.target(0, 10_000, SkipHeadTail(6, 4)) == null)
            policy.reset(); check(policy.target(20_000, 100_000, SkipHeadTail(10, 5)) == null)
            check(store.save(3544, SkipHeadTail(10, 5))); check(store.get(3544) == SkipHeadTail(10, 5))
            val future = MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync()
            val player = future.get(20, TimeUnit.SECONDS); controller = player
            checkEventually("存储准备") { player.sessionExtras.getBoolean("storageReady") }
            runOnMainSync { player.setMediaItem(PlaybackItems.mediaItem(Item(222612,"略过头尾测试",true,3544,page=1))); player.prepare(); player.play() }
            checkEventually("播放并跳过片头") { player.isPlaying && player.currentPosition >= 10_000 && player.duration > 20_000 }
            runOnMainSync { player.seekTo(0) }
            Thread.sleep(2200)
            runOnMainSync { check(player.currentPosition in 0..8000) }
            runOnMainSync { player.seekTo(player.duration - 6000) }
            checkEventually("片尾后进入下一集") { player.currentMediaItem?.let(PlaybackItems::item)?.id != 222612L }
            checkEventually("跳过片尾后原节目记录为完成") { ListeningStore.get(targetContext).state.value.history.any { it.id == 222612L && it.completed } }
            result.putString("result", "PASS: policy, per-album persistence, real intro skip, manual seek and outro auto-advance")
            finish(-1,result)
        } catch (t: Throwable) { result.putString("failure", t.stackTraceToString()); finish(0,result) }
        finally { store.save(3544, previous); controller?.let { runOnMainSync { it.pause(); it.release() } } }
    }
}
