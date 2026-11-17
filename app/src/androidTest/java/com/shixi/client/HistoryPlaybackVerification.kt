package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.TimeUnit

/** Real public audio, with the same missing-page history shape returned by cloud sync. */
class HistoryPlaybackVerification : Instrumentation() {
    private var baseline = false
    override fun onCreate(arguments: Bundle?) { baseline = arguments?.getString("baseline") == "true"; super.onCreate(arguments); start() }
    private fun <T> main(block: () -> T): T {
        var result: T? = null; var failure: Throwable? = null
        runOnMainSync { try { result = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 60_000
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
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                target = target.parent
            }
        }
        return false
    }
    override fun onStart() {
        val result = Bundle(); var controller: MediaController? = null; var activity: android.app.Activity? = null
        try {
            val api = AccountContentApi(targetContext)
            val episode = api.tracks(257813, 2).first { it.id == 2971005L }
            check(episode.free && episode.page == 2)
            val store = ListeningStore.get(targetContext)
            val epoch = store.attachPlayer()
            store.history(epoch, HistoryRow(id = episode.id, albumId = episode.albumId, page = 1,
                title = episode.title, cover = episode.cover, positionMs = 1500, durationMs = 600000,
                completed = false, updatedAt = System.currentTimeMillis()))
            await("history fixture saved") { store.state.value.history.firstOrNull()?.id == episode.id }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("home history") {
                click("Don't Show Again"); click("Don't show again"); click("OK"); click("确定")
                nodes("继续播放").isNotEmpty()
            }
            val future = main { MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
            val player = future.get(20, TimeUnit.SECONDS); controller = player
            await("player ready") { main { player.sessionExtras.getBoolean("storageReady") } }
            check(click("继续播放"))
            if (baseline) {
                await("original wrong-page error") { main { player.playerError != null && PlaybackItems.item(player.currentMediaItem!!)?.page == 1 } }
                result.putString("result", "REPRODUCED: home locates page 2 but starts page 1; actual player fails")
            } else {
                await("correct page and real audio") { main { player.isPlaying && player.currentPosition > 3000 && PlaybackItems.item(player.currentMediaItem!!)?.page == 2 } }
                result.putLong("positionMs", main { player.currentPosition })
                check(main { player.playerError == null && player.sessionExtras.getString("albumIssue").isNullOrEmpty() })
                main { player.pause() }
                await("paused") { main { !player.playWhenReady } }
                check(click("继续播放"))
                await("resume real audio") { main { player.isPlaying && player.currentPosition > 4500 } }
                main { player.pause(); player.setMediaItem(PlaybackItems.mediaItem(episode.copy(page = 1)), 1500) }
                await("restored mini player") { nodes("播放").isNotEmpty() }
                check(click("播放"))
                await("mini player repairs restored page") { main { player.isPlaying && player.currentPosition > 3000 && PlaybackItems.item(player.currentMediaItem!!)?.page == 2 } }
                main { player.pause(); player.setMediaItem(PlaybackItems.mediaItem(episode.copy(page = 1)), 1500); player.prepare(); player.play() }
                await("old queued page fails") { main { player.playerError != null && !player.playWhenReady } }
                await("retry action") { nodes("重试").isNotEmpty() }
                check(click("重试"))
                await("retry repairs old page") { main { player.isPlaying && player.currentPosition > 3000 && PlaybackItems.item(player.currentMediaItem!!)?.page == 2 } }
                result.putString("result", "PASS: history, mini player and error retry repair missing page; real audio, pause and resume")
            }
            finish(-1, result)
        } catch (failure: Throwable) { result.putString("failure", failure.stackTraceToString()); finish(0, result) }
        finally { main { controller?.pause(); controller?.release(); activity?.finish() } }
    }
}
