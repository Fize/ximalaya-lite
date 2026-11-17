package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.shixi.protocol.Item
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Separate instrumentation processes exercise the durable cold-start cursor. */
class PausedPlaybackVerification : Instrumentation() {
    private var phase = "verify"
    override fun onCreate(arguments: Bundle?) { phase = arguments?.getString("phase") ?: phase; super.onCreate(arguments); start() }
    private fun <T> main(block: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(label: String, seconds: Int = 30, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + seconds * 1000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(50) }
        error("timeout: $label")
    }
    private fun nodes(text: String): List<AccessibilityNodeInfo> {
        uiAutomation.clearCache()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node)
            repeat(node.childCount) { walk(node.getChild(it)) }
        }
        walk(uiAutomation.rootInActiveWindow); return found
    }
    private fun click(text: String): Boolean {
        for (node in nodes(text)) {
            var parent: AccessibilityNodeInfo? = node
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                parent = parent.parent
            }
        }
        return false
    }
    private fun slider(): AccessibilityNodeInfo {
        var node = nodes("播放进度").first()
        while (node.rangeInfo == null) node = checkNotNull(node.parent)
        return node
    }
    override fun onStart() {
        val result = Bundle(); var controller: MediaController? = null; var activity: android.app.Activity? = null
        val title = "第21夜：小熊请客-飞飞姐姐"
        val fixture = Item(2971005, title, false, 257813, "", 1) // Actual catalogue page is 2.
        try {
            if (phase == "seed" || phase == "seed_unknown") {
                val db = ListeningDatabase.open(targetContext)
                runBlocking {
                    db.records().save(PlaybackSnapshot(listOf(fixture), 0, 70_000, 1f, 0),
                        HistoryRow(id = fixture.id, albumId = fixture.albumId, page = 1, title = title, cover = "",
                            positionMs = 70_000, durationMs = if (phase == "seed") 600_000 else 0,
                            completed = false, updatedAt = System.currentTimeMillis()))
                }
                db.close()
                finish(-1, Bundle().apply { putString("result", "PASS seeded isolated cold-start cursor") }); return
            }
            val future = main { MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
            val player = future.get(20, TimeUnit.SECONDS); controller = player
            await("restored cursor") { main { player.sessionExtras.getBoolean("storageReady") && player.currentMediaItem != null } }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("mini player") { click("Don't Show Again"); click("Don't show again"); click("OK"); nodes(title).isNotEmpty() }
            check(click(title))
            await("player progress") { nodes("播放进度").isNotEmpty() }
            if (phase == "unknown") {
                check(nodes("--:--").isNotEmpty())
                check(!slider().isEnabled)
                check(main { player.playbackState == Player.STATE_IDLE && !player.playWhenReady && player.currentPosition == 70_000L })
                result.putString("result", "PASS unknown duration is --:--; original saved position retained without preparing audio")
            } else if (phase == "restart") {
                check(main { player.currentPosition == 60_000L && player.playbackState == Player.STATE_IDLE && !player.playWhenReady })
                check(slider().let { it.isEnabled && it.rangeInfo?.current == 60_000f })
                check(main { player.sessionExtras.getLong("manualSeekAt") > 0 }) { "opening restored player cleared manual cursor" }
                check(click("播放"))
                await("restart plays selected position", 60) { main { player.isPlaying && player.currentPosition in 60_000..66_000 } }
                result.putString("result", "PASS process restart restores manually adjusted cursor and real audio begins at 60s")
            } else {
                check(main { player.currentPosition == 70_000L && player.playbackState == Player.STATE_IDLE && !player.playWhenReady })
                val slider = slider()
                check(slider.isEnabled && slider.rangeInfo?.current == 70_000f && slider.rangeInfo?.max == 600_000f)
                fun adjust(point: Long) {
                    val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, point.toFloat()) }
                    check(slider().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                    await("seek applied $point") { main { player.currentPosition == point && player.sessionExtras.getLong("manualSeekTrack") == fixture.id } }
                    await("cursor persisted $point") { runBlocking { ListeningDatabase.open(targetContext).let { db -> try { db.records().cursor()?.positionMs == point } finally { db.close() } } } }
                }
                adjust(45_000)
                check(main { !player.playWhenReady && player.playbackState == Player.STATE_IDLE })
                val rejected = main { player.sendCustomCommand(SessionCommand("com.shixi.client.CURSOR", Bundle.EMPTY), Bundle().apply {
                    putLong("trackId", fixture.id + 1); putLong("albumId", fixture.albumId); putLong("positionMs", 1)
                }) }.get(5, TimeUnit.SECONDS)
                check(rejected.resultCode == androidx.media3.session.SessionError.ERROR_BAD_VALUE)
                check(main { player.currentPosition == 45_000L && !player.playWhenReady })
                check(click("播放"))
                await("page-corrected playback keeps adjusted point", 60) { main {
                    player.isPlaying && player.currentPosition in 45_000..51_000 && player.currentMediaItem?.let(PlaybackItems::item)?.page == 2
                } }
                val first = main { player.currentPosition }
                check(click("暂停")); await("paused") { main { !player.playWhenReady } }
                adjust(30_000)
                check(click("播放"))
                await("prepared pause seek resumes", 60) { main { player.isPlaying && player.currentPosition in 30_000..36_000 } }
                check(click("暂停")); await("second pause") { main { !player.playWhenReady } }
                adjust(20_000)
                main { player.play() } // MediaSession path, without the app's startTrack.
                await("external resume advances displayed cursor", 20) {
                    main { player.isPlaying && player.currentPosition > 22_000 } && slider().rangeInfo!!.current > 22_000f
                }
                main { player.pause() }; await("external pause") { main { !player.playWhenReady } }
                val beforeForward = main { player.currentPosition }
                check(click("前进30秒"))
                await("relative seek uses advanced external cursor") {
                    main { player.currentPosition in (beforeForward + 29_000)..(beforeForward + 31_000) && !player.playWhenReady }
                }
                adjust(60_000)
                main { player.sendCustomCommand(SessionCommand("com.shixi.client.SAVE", Bundle.EMPTY), Bundle.EMPTY) }.get(5, TimeUnit.SECONDS)
                runBlocking { ListeningStore.get(targetContext).awaitIdle() }
                val saved = runBlocking { ListeningStore.get(targetContext).restore().await() }
                check(saved.manualSeekAt > 0) { "manual cursor was not saved" }
                result.putLong("savedManualAt", saved.manualSeekAt)
                result.putLong("firstAudiblePositionMs", first)
                result.putString("result", "PASS cold paused duration+position; UI seek with no prepare; stale track rejected; page correction starts real audio at45s; prepared seek resumes30s; external resume advances UI and relative seek; paused60s persisted for restart")
            }
            finish(-1, result)
        } catch (failure: Throwable) { result.putString("failure", failure.stackTraceToString()); finish(0, result) }
        finally { main { controller?.pause(); controller?.release(); activity?.finish() } }
    }
}
