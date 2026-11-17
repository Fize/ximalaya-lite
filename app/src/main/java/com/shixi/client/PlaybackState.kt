package com.shixi.client

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.shixi.protocol.Item

data class PlaybackSnapshot(val queue: List<Item>, val index: Int, val positionMs: Long,
    val speed: Float, val updatedElapsedMs: Long, val manualSeekAt: Long = 0)

/** In-memory observation; durable writes use the store FIFO, not a conflating flow collector. */
object PlaybackSnapshots {
    private val mutable = MutableStateFlow<PlaybackSnapshot?>(null)
    val latest = mutable.asStateFlow()
    fun publish(snapshot: PlaybackSnapshot) { mutable.value = snapshot }
}

object PlaybackItems {
    fun duration(player: MediaController): Long = player.duration.takeIf { it > 0 }
        ?: player.sessionExtras.takeIf { it.getString("durationMediaId") == player.currentMediaItem?.mediaId }
            ?.getLong("durationMs")?.coerceAtLeast(0) ?: 0

    fun mediaItem(track: Item): MediaItem = MediaItem.Builder().setMediaId("${track.id}:${track.albumId}:${track.page}")
        .setUri("shixi://track/${track.id}/${track.albumId}/${track.page}")
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(track.title).setDisplayTitle(track.title)
            .setSubtitle("拾昔").setArtist("").setAlbumArtist("").setArtworkUri(track.cover.takeIf { it.startsWith("https://") }?.let { android.net.Uri.parse(it) }).build()).build()

    fun item(media: MediaItem): Item? {
        val parts = media.mediaId.split(":")
        val id = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val album = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val page = parts.getOrNull(2)?.toIntOrNull() ?: return null
        if (id <= 0 || album <= 0 || page <= 0) return null
        return Item(id, media.mediaMetadata.title?.toString().orEmpty(), false, album,
            media.mediaMetadata.artworkUri?.toString().orEmpty(), page)
    }
    fun status(player: MediaController): String {
        if (!player.sessionExtras.getBoolean("storageReady", false)) return "正在准备"
        val issue = player.sessionExtras.getString("playbackIssue").orEmpty()
        if (issue.isNotBlank()) return "暂时无法播放，请重试"
        if (player.playerError != null) return "播放失败，请重新连接"
        if (player.mediaItemCount == 0) return "未选择节目"
        if (player.playbackState == Player.STATE_ENDED) return if (player.sessionExtras.getBoolean("albumAfter")) "等待加载下一集" else "本专辑已播完"
        if (!player.playWhenReady) return "已暂停"
        if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return "等待继续播放"
        return if (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE) "正在加载" else "正在播放"
    }
}
