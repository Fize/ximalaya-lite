package com.shixi.client

import android.os.SystemClock
import com.shixi.protocol.Item
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Short-lived public catalogue data, shared by the playlist and playback lookup.
 * Playback authorization and addresses are always checked separately. */
internal class PlaybackCatalogue(
    private val fetch: (Long, Int) -> List<Item>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Entry(val created: Long, val task: FutureTask<List<Item>>)
    private val entries = LinkedHashMap<Pair<Long, Int>, Entry>()

    fun tracks(album: Long, page: Int): List<Item> {
        val key = album to page
        val entry = synchronized(this) {
            entries[key]?.takeIf { clock() - it.created < 30_000 } ?: Entry(clock(), FutureTask { fetch(album, page) }).also {
                if (entries.size >= 8) entries.remove(entries.keys.first())
                entries[key] = it
            }
        }
        entry.task.run()
        try { return entry.task.get() }
        catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        catch (failure: Exception) {
            synchronized(this) { if (entries[key] === entry) entries.remove(key) }
            if (failure is ExecutionException) throw (failure.cause ?: failure)
            throw failure
        }
    }
}
