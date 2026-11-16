package com.shixi.client

import androidx.media3.common.Player
import com.shixi.protocol.Item
import kotlinx.coroutines.*

/** Main-thread owner of a contiguous, ascending window of one album. */
internal class AlbumPlaybackQueue(
    private val player: Player,
    private val scope: CoroutineScope,
    private val identity: () -> Any,
    private val fetch: (Long, Int) -> List<Item>,
    private val changed: () -> Unit,
) {
    var loading = false; private set
    var issue = ""; private set
    var ready = false; private set
    var before = false; private set
    var after = false; private set
    private var firstPage = 1
    private var lastPage = 1
    private var album = 0L
    private var generation = 0
    val version: Int get() = generation
    private var job: Job? = null
    private var step = 0
    private var advance = false
    private var failedDirection = 0

    fun cancelIntent() { step = 0; advance = false }
    fun reset() {
        generation++; job?.cancel(); job = null; loading = false; issue = ""; ready = false
        cancelIntent()
        val item = player.currentMediaItem?.let(PlaybackItems::item)
        album = item?.albumId ?: 0
        firstPage = item?.page ?: 1; lastPage = firstPage
        before = firstPage > 1; after = item != null
        changed()
    }
    fun close() { reset(); album = 0 }
    fun ensure() { if (album > 0 && !ready && !loading && issue.isEmpty()) load(0) else prefetch() }
    fun retry() { issue = ""; advance = player.playbackState == Player.STATE_ENDED && player.playWhenReady && after; load(if (ready) failedDirection else 0) }
    fun more(direction: Int) { if (issue.isEmpty()) load(if (ready) direction else 0) }
    fun move(direction: Int) {
        cancelIntent()
        if (direction < 0 && player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
        else if (direction > 0 && player.hasNextMediaItem()) player.seekToNextMediaItem()
        else if (if (direction < 0) before else after) { step = direction; issue = ""; load(if (ready) direction else 0) }
    }
    fun ended(): Boolean {
        if (!after || !player.playWhenReady) return false
        advance = true
        if (issue.isEmpty()) load(if (ready) 1 else 0)
        return true
    }
    fun prefetch() {
        if (!ready || loading || issue.isNotEmpty()) return
        if (before && player.currentMediaItemIndex < 3) load(-1)
        else if (after && player.mediaItemCount - player.currentMediaItemIndex <= 4) load(1)
    }
    private fun load(direction: Int) {
        if (loading || album <= 0 || ready && (direction < 0 && !before || direction > 0 && !after)) return
        val selected = player.currentMediaItem?.let(PlaybackItems::item) ?: return
        val page = if (!ready) selected.page else if (direction < 0) firstPage - 1 else lastPage + 1
        val initial = !ready
        val ticket = generation
        val owner = identity()
        val albumId = album
        loading = true; issue = ""; failedDirection = direction; changed()
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val rows = withContext(Dispatchers.IO) { fetch(albumId, page) }
                if (ticket != generation || identity() != owner || player.currentMediaItem?.let(PlaybackItems::item)?.albumId != albumId) return@launch
                require(rows.all { it.albumId == albumId && it.page == page } && rows.map { it.id }.distinct().size == rows.size)
                val current = player.currentMediaItem?.let(PlaybackItems::item) ?: return@launch
                if (initial) {
                    val index = rows.indexOfFirst { it.id == current.id }
                    require(index >= 0) { "Current episode absent from album page" }
                    // Keep the playing item and decoder; surrounding entries come from the catalogue.
                    if (player.mediaItemCount > 1) {
                        val currentIndex = player.currentMediaItemIndex
                        player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
                        player.removeMediaItems(0, currentIndex)
                    }
                    player.addMediaItems(0, rows.take(index).map(PlaybackItems::mediaItem))
                    player.addMediaItems(rows.drop(index + 1).map(PlaybackItems::mediaItem))
                    firstPage = page; lastPage = page; before = page > 1; after = rows.size == 20; ready = true
                } else {
                    val ids = (0 until player.mediaItemCount).mapNotNull { PlaybackItems.item(player.getMediaItemAt(it))?.id }.toSet()
                    require(rows.none { it.id in ids }) { "Overlapping album pages" }
                    if (direction < 0) {
                        require(rows.isNotEmpty())
                        player.addMediaItems(0, rows.map(PlaybackItems::mediaItem)); firstPage = page; before = page > 1
                    } else {
                        player.addMediaItems(rows.map(PlaybackItems::mediaItem)); lastPage = page; after = rows.size == 20
                    }
                }
                val pending = step
                if (pending < 0 && player.hasPreviousMediaItem()) { step = 0; player.seekToPreviousMediaItem() }
                val continuePlaying = advance && player.playWhenReady
                if ((pending > 0 || continuePlaying) && player.hasNextMediaItem()) {
                    val resume = continuePlaying; advance = false; step = 0
                    player.seekToNextMediaItem()
                    if (resume) { player.prepare(); player.play() }
                }
                if (!after) {
                    advance = false; if (step > 0) step = 0
                    if (player.playbackState == Player.STATE_ENDED && !player.hasNextMediaItem()) player.pause()
                }
                if (!before && step < 0) step = 0
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == generation && identity() == owner) { issue = "选集加载失败，请重试"; cancelIntent() } }
            finally {
                if (ticket == generation) { loading = false; job = null; changed(); if (issue.isEmpty()) { if (step != 0) load(step) else prefetch() } }
            }
        }.also { it.start() }
    }
}
