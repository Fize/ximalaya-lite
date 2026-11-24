package com.shixi.client

import android.net.Uri
import android.os.SystemClock
import com.shixi.protocol.ContentApi
import com.shixi.protocol.Item
import java.io.IOException

class PlaybackAccessException : IOException("当前节目没有明确的收听权限")

/** Memory-only, short-lived addresses. Every fresh resolution rechecks the programme flags. */
class PlaybackResolver(private val tracks: (Long, Int) -> List<Item>, private val address: (Item) -> String,
    private val clock: () -> Long = SystemClock::elapsedRealtime, private val owner: () -> Any = { Unit }) {
    private data class Cached(val url: Uri, val expires: Long, val owner: Any)
    private val cache = LinkedHashMap<String, Cached>()
    private var generation = 0L
    fun resolve(uri: Uri): Uri {
        if (uri.scheme != "shixi" || uri.host != "track" || uri.pathSegments.size != 3 || uri.query != null)
            throw IOException("不支持的媒体地址")
        val parts = uri.pathSegments
        val id = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("无效节目")
        val album = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("无效专辑")
        val page = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: throw IOException("无效页码")
        val identity = owner()
        val key = uri.toString()
        val current = synchronized(this) {
            cache[key]?.takeIf { it.expires > clock() && it.owner == identity }?.let { return it.url }
            generation
        }
        StartupTiming.mark("catalogue_start")
        val item = tracks(album, page).firstOrNull { it.id == id } ?: throw PlaybackAccessException()
        StartupTiming.mark("catalogue_done")
        StartupTiming.mark("authorization_start")
        val result = Uri.parse(address(item))
        StartupTiming.mark("authorization_done")
        if (result.scheme != "https" || result.host.isNullOrBlank()) throw IOException("未取得安全的播放地址")
        synchronized(this) {
            if (generation != current || owner() != identity)
                throw com.shixi.protocol.ContentAccessException(com.shixi.protocol.ListeningAccess.UNKNOWN, "账号会话已改变，请重新选择节目", sessionChanged = true)
            if (generation == current) {
                if (cache.size >= 8) cache.remove(cache.keys.first())
                cache[key] = Cached(result, clock() + 120_000, identity)
            }
        }
        return result
    }
    @Synchronized fun invalidate(uri: Uri?) { generation++; if (uri == null) cache.clear() else cache.remove(uri.toString()) }
    companion object {
        fun live(context: android.content.Context): PlaybackResolver { val api = AccountContentApi(context); return PlaybackResolver(api::tracks, api::resolve, owner = AccountManager.get(context)::contentIdentity) }
    }
}
