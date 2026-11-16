package com.shixi.client

import android.os.Handler
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/** Main-thread coordinator: at most two automatic retries per item/user intent. */
class PlaybackRecovery(private val player: Player, private val resolver: PlaybackResolver,
    private val handler: Handler, private val online: () -> Boolean, private val changed: () -> Unit) {
    private data class Ticket(val generation: Long, val mediaId: String, val position: Long)
    private var generation = 0L
    private var pending: Ticket? = null
    private var scheduled: Runnable? = null
    var attempts = 0; private set
    var issue = ""; private set
    private fun status(value: String) { issue = value; changed() }
    fun cancel(clearIssue: Boolean = true, resetBudget: Boolean = true) {
        generation++; pending = null; scheduled?.let(handler::removeCallbacks); scheduled = null
        if (resetBudget) attempts = 0
        if (clearIssue) status("")
    }
    fun accountChanged(interrupted: Boolean = true) { cancel(); if (interrupted) status("账号会话已改变，播放已停止，请重新选择节目") }
    fun userIntent() {
        cancel()
        if (player.playerError != null) resolver.invalidate(player.currentMediaItem?.localConfiguration?.uri)
    }
    fun onError(error: PlaybackException) {
        // A paused/prepared source can fail while its playlist is being expanded.
        // Keep ExoPlayer's error for explicit-play recovery, without announcing a failed user action.
        if (!player.playWhenReady) { cancel(); return }
        val denied = generateSequence<Throwable>(error) { it.cause }.firstOrNull {
            it is PlaybackAccessException || it is com.shixi.protocol.ContentAccessException
        }
        if (denied != null) {
            cancel(); status((denied as? com.shixi.protocol.ContentAccessException)?.message?.plus("，已停止")
                ?: "当前节目无明确收听权限，已停止")
            player.pause(); return
        }
        val recoverable = error.errorCode in setOf(PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        if (!recoverable || !player.playWhenReady) { status("播放失败，请手动重新连接"); return }
        if (attempts >= 2) { pending = null; status("自动重连已达上限，请检查网络后重新连接"); return }
        val id = player.currentMediaItem?.mediaId ?: return
        pending = Ticket(++generation, id, player.currentPosition.coerceAtLeast(0))
        tryPending()
    }
    fun tryPending() {
        val ticket = pending ?: return
        if (ticket.generation != generation || player.currentMediaItem?.mediaId != ticket.mediaId || !player.playWhenReady) {
            cancel(); return
        }
        if (!online()) { status("网络不可用，等待恢复后重连"); return }
        if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            status("等待音频焦点恢复"); return
        }
        if (scheduled != null) return
        status("正在重新连接（${attempts + 1}/2）")
        scheduled = Runnable {
            scheduled = null
            if (pending != ticket || ticket.generation != generation || player.currentMediaItem?.mediaId != ticket.mediaId || !player.playWhenReady) return@Runnable
            if (!online() || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) { tryPending(); return@Runnable }
            pending = null; attempts++
            resolver.invalidate(player.currentMediaItem?.localConfiguration?.uri)
            player.seekTo(ticket.position)
            player.prepare()
        }.also { handler.postDelayed(it, if (attempts == 0) 1_000 else 3_000) }
    }
    fun onPlaying() { if (issue.isNotEmpty()) status("") }
}
