package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.TimeUnit

/** Reported regression: the comments panel must follow the episode chosen with 上一集/下一集. */
class CommentsEpisodeSwitchVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle(); var player: MediaController? = null; var activity: android.app.Activity? = null
        fun nodes(label: String): List<AccessibilityNodeInfo> { uiAutomation.clearCache(); val list = mutableListOf<AccessibilityNodeInfo>(); fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; if (n.text?.toString() == label || n.contentDescription?.toString() == label) list.add(n); for (i in 0 until n.childCount) walk(n.getChild(i)) }; walk(uiAutomation.rootInActiveWindow); return list }
        fun waitFor(label: String, check: () -> Boolean) { val end = SystemClock.uptimeMillis() + 25000; while (SystemClock.uptimeMillis() < end) { if (check()) return; Thread.sleep(100) }; error(label) }
        fun click(label: String) { waitFor(label) { nodes(label).any { node -> var n: AccessibilityNodeInfo? = node; var done = false; while (n != null && !done) { if (n.isClickable && n.isEnabled) done = n.performAction(AccessibilityNodeInfo.ACTION_CLICK); n = n.parent }; done } } }
        fun currentId(): Long { var id = 0L; runOnMainSync { id = player?.currentMediaItem?.let(PlaybackItems::item)?.id ?: 0L }; return id }
        try {
            val api = AccountContentApi(targetContext)
            // Adjacent episodes with distinct first comments, so a stale panel is visible.
            val pair = api.tracks(3544, 1).zipWithNext().firstOrNull { (a, b) ->
                val first = runCatching { api.episodeComments(a.id, 1).rows }.getOrNull().orEmpty()
                val second = runCatching { api.episodeComments(b.id, 1).rows }.getOrNull().orEmpty()
                first.isNotEmpty() && second.isNotEmpty() && first.first().content != second.first().content
            } ?: error("album 3544 has no adjacent pair with distinct first comments")
            val (episode, following) = pair
            val oldComment = api.episodeComments(episode.id, 1).rows.first().content
            val newComment = api.episodeComments(following.id, 1).rows.first().content
            val controller = MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync().get(20, TimeUnit.SECONDS)
            player = controller
            waitFor("storage") { var ready = false; runOnMainSync { ready = controller.sessionExtras.getBoolean("storageReady") }; ready }
            runOnMainSync { controller.setMediaItem(PlaybackItems.mediaItem(episode)); controller.pause() }
            waitFor("album page queues the following episode") { var queued = false; runOnMainSync { queued = (0 until controller.mediaItemCount).any { PlaybackItems.item(controller.getMediaItemAt(it))?.id == following.id } }; queued }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("home") { nodes("OK").isNotEmpty() || nodes(episode.title).isNotEmpty() }
            if (nodes("OK").isNotEmpty()) click("OK")
            waitFor("mini player") { nodes(episode.title).isNotEmpty() }
            val bounds = android.graphics.Rect(); nodes(episode.title).first().getBoundsInScreen(bounds)
            uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            waitFor("player") { nodes("评论").isNotEmpty() && nodes("点赞").isNotEmpty() }
            click("评论"); waitFor("comments opened") { nodes("写评论").isNotEmpty() }
            waitFor("first episode comments") { nodes(oldComment).isNotEmpty() }
            waitFor("header names the first episode") { nodes("评论 · ${episode.title}").isNotEmpty() }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitFor("comments closed") { nodes("写评论").isEmpty() }
            click("下一集"); waitFor("switched to the following episode") { currentId() == following.id }
            waitFor("player title follows") { nodes(following.title).isNotEmpty() }
            click("评论"); waitFor("comments reopened") { nodes("写评论").isNotEmpty() }
            waitFor("header follows the episode") { nodes("评论 · ${following.title}").isNotEmpty() }
            waitFor("following episode comments") { nodes(newComment).isNotEmpty() }
            check(nodes(oldComment).isEmpty()) { "comments from the previous episode are still shown" }
            // Continuous playback changes the episode while the panel stays mounted.
            runOnMainSync { controller.seekToPreviousMediaItem() }
            waitFor("switched back under the open panel") { currentId() == episode.id }
            waitFor("open panel follows the episode") { nodes("评论 · ${episode.title}").isNotEmpty() && nodes(oldComment).isNotEmpty() }
            check(nodes("评论 · ${following.title}").isEmpty()) { "open panel still names the previous episode" }
            result.putString("result", "PASS comments panel follows 上一集/下一集 both closed and open: header names the episode and the list is refetched")
            finish(-1, result)
        } catch (t: Throwable) { result.putString("failure", t.stackTraceToString()); finish(0, result) }
        finally { activity?.let { runOnMainSync { it.finish() } }; player?.let { runOnMainSync { it.pause(); it.release() } } }
    }
}
