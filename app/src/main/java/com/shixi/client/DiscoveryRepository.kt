package com.shixi.client

import android.content.Context
import android.util.AtomicFile
import com.shixi.protocol.AlbumBrief
import com.shixi.protocol.AlbumDetail
import com.shixi.protocol.AnchorAlbums
import com.shixi.protocol.AnchorProfile
import com.shixi.protocol.CategoryGroup
import com.shixi.protocol.CategoryPage
import com.shixi.protocol.DiscoveryApi
import com.shixi.protocol.LiveRoom
import com.shixi.protocol.RadioHome
import com.shixi.protocol.RankCategory
import com.shixi.protocol.RankGroup
import com.shixi.protocol.RankPage
import com.shixi.protocol.RecommendFeed
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Discovery data with the TTLs agreed in the implementation plan. Public
 * metadata only; the optional identity is used solely to let the recommendation
 * endpoint answer for the signed-in account.
 */
class DiscoveryRepository internal constructor(
    private val api: DiscoveryApi,
    private val filesDir: File?,
    private val identity: () -> String?,
    private val clock: () -> Long,
    private val io: kotlin.coroutines.CoroutineContext,
    scope: CoroutineScope,
) {
    companion object {
        private const val RECOMMEND_TTL = 10 * 60_000L
        private const val CATEGORY_TREE_TTL = 24 * 60 * 60_000L
        private const val CATEGORY_PAGE_TTL = 30 * 60_000L
        private const val RANK_TTL = 30 * 60_000L
        private const val ANCHOR_TTL = 60 * 60_000L
        private const val RADIO_TTL = 5 * 60_000L
        private const val ALBUM_TTL = 60 * 60_000L
        private const val LIVE_ROOM_TTL = 30_000L
        private const val LIVE_STREAM_TTL = 60_000L

        @Volatile private var instance: DiscoveryRepository? = null

        fun get(context: Context): DiscoveryRepository = instance ?: synchronized(this) {
            instance ?: DiscoveryRepository(
                api = DiscoveryApi(),
                filesDir = context.applicationContext.filesDir,
                identity = { ContentPreferenceStore.get(context).recommendCookie(context) },
                clock = System::currentTimeMillis,
                io = Dispatchers.IO,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { instance = it }
        }
    }

    private class Stamp<T>(val at: Long, val value: T)

    private val disk = filesDir?.let { RawDiskCache(File(it, "discovery-cache-v2.json")) }
        .also { filesDir?.let { dir -> File(dir, "discovery-cache-v1.json").delete() } }

    private val locks = HashMap<String, Mutex>()
    private val memo = HashMap<String, Stamp<Any>>()
    private val rawMemo = HashMap<String, Stamp<String>>()

    private fun lock(key: String): Mutex = synchronized(locks) { locks.getOrPut(key) { Mutex() } }

    @Suppress("UNCHECKED_CAST")
    private fun <T> peek(key: String, ttl: Long, force: Boolean): T? = synchronized(memo) {
        val stamp = memo[key] as? Stamp<T> ?: return null
        if (force || clock() - stamp.at > ttl) null else stamp.value
    }

    private fun <T> put(key: String, value: T) { synchronized(memo) { memo[key] = Stamp(clock(), value as Any) } }

    private suspend fun <T> cached(key: String, ttl: Long, force: Boolean, loader: suspend () -> T): T {
        peek<T>(key, ttl, force)?.let { return it }
        return lock(key).withLock {
            peek<T>(key, ttl, force)?.let { return@withLock it }
            withContext(io) { loader() }.also { put(key, it) }
        }
    }

    private suspend fun raw(key: String, ttl: Long, force: Boolean, loader: suspend () -> String): String {
        if (!force) {
            synchronized(rawMemo) { rawMemo[key]?.takeIf { clock() - it.at <= ttl } }?.let { return it.value }
            disk?.read(key)?.takeIf { clock() - it.first <= ttl }?.let { cached ->
                synchronized(rawMemo) { rawMemo[key] = Stamp(cached.first, cached.second) }
                return cached.second
            }
        }
        return lock("raw:$key").withLock {
            if (!force) {
                synchronized(rawMemo) { rawMemo[key]?.takeIf { clock() - it.at <= ttl } }?.let { return@withLock it.value }
            }
            val body = try {
                withContext(io) { loader() }
            } catch (failure: Exception) {
                // Serve stale data rather than an empty screen when the network is down.
                val stale = synchronized(rawMemo) { rawMemo[key] }?.value ?: disk?.read(key)?.second
                if (stale != null) return@withLock stale
                throw failure
            }
            val at = clock()
            synchronized(rawMemo) { rawMemo[key] = Stamp(at, body) }
            disk?.write(key, at, body)
            body
        }
    }

    // ---- recommendation ----

    suspend fun recommend(refresh: Boolean = false): RecommendFeed {
        val cookie = identity()
        // Cache per identity so switching the preference or signing out cannot reuse another body.
        val identityKey = "recommend-" + (cookie?.hashCode()?.toString() ?: "anon")
        return cached(identityKey, RECOMMEND_TTL, refresh) {
            var sections: List<com.shixi.protocol.RecommendSection> = emptyList()
            var guess: List<AlbumBrief> = emptyList()
            var firstFailure: Throwable? = null
            runCatching {
                com.shixi.protocol.DiscoveryParsers.recommendFeed(
                    JSONObject(raw("$identityKey-cards", RECOMMEND_TTL, refresh) { api.rawRecommend(cookie) })
                ).sections
            }.onSuccess { sections = it }.onFailure { firstFailure = it }
            runCatching {
                com.shixi.protocol.DiscoveryParsers.guessYouLike(
                    JSONObject(raw("$identityKey-guess", RECOMMEND_TTL, refresh) { api.rawGuessYouLike(cookie) })
                )
            }.onSuccess { guess = it }.onFailure { if (firstFailure == null) firstFailure = it }
            if (sections.isEmpty() && guess.isEmpty()) {
                throw firstFailure ?: com.shixi.protocol.DiscoveryException("推荐内容暂时不可用")
            }
            RecommendFeed(guessYouLike = guess, sections = sections)
        }
    }

    // ---- categories ----

    suspend fun categoryGroups(refresh: Boolean = false): List<CategoryGroup> =
        cached("category-groups", CATEGORY_TREE_TTL, refresh) {
            com.shixi.protocol.DiscoveryParsers.categoryGroups(
                JSONObject(raw("category-breadcrumb", CATEGORY_TREE_TTL, refresh) { api.rawCategoryBreadcrumb() }),
                JSONObject(raw("category-all", CATEGORY_TREE_TTL, refresh) { api.rawCategoryAll() }),
            )
        }

    suspend fun categoryPage(code: String, refresh: Boolean = false): CategoryPage =
        cached("category-page:$code", CATEGORY_PAGE_TTL, refresh) { api.categoryPage(code) }

    suspend fun categoryAlbums(
        code: String,
        subCode: String?,
        sort: Int,
        metas: List<Pair<Long, Long>>,
        page: Int,
    ): List<AlbumBrief> {
        val key = "category-albums:$code|${subCode.orEmpty()}|$sort|${metas.joinToString("-")}|$page"
        return cached(key, CATEGORY_PAGE_TTL, false) { api.categoryAlbums(code, subCode, sort, metas, page) }
    }

    // ---- ranks ----

    suspend fun rankGroups(refresh: Boolean = false): List<RankGroup> =
        cached("rank-groups", RANK_TTL, refresh) { api.rankGroups() }

    suspend fun rankCategories(clusterCode: String, refresh: Boolean = false): List<RankCategory> =
        cached("rank-categories:$clusterCode", RANK_TTL, refresh) { api.rankCategories(clusterCode) }

    suspend fun rankPage(clusterCode: String, categoryCode: String, refresh: Boolean = false): RankPage =
        cached("rank:$clusterCode|$categoryCode", RANK_TTL, refresh) { api.rankPage(clusterCode, categoryCode) }

    // ---- anchors ----

    suspend fun anchorProfile(uid: Long, refresh: Boolean = false): AnchorProfile? =
        cached("anchor:$uid", ANCHOR_TTL, refresh) { api.anchorProfile(uid) }

    suspend fun anchorAlbums(uid: Long, refresh: Boolean = false): AnchorAlbums =
        cached("anchor-albums:$uid", ANCHOR_TTL, refresh) { api.anchorAlbums(uid) }

    // ---- radio / live ----

    suspend fun radioHome(refresh: Boolean = false): RadioHome =
        cached("radio-home", RADIO_TTL, refresh) { api.radioHome() }

    suspend fun liveRoom(roomId: Long, refresh: Boolean = false): LiveRoom? =
        cached("live-room:$roomId", LIVE_ROOM_TTL, refresh) { api.liveRoom(roomId) }

    suspend fun liveRooms(refresh: Boolean = false): List<LiveRoom> =
        cached("live-rooms", 60_000L, refresh) { api.mobileRecommend().liveRooms }

    suspend fun liveStream(liveId: Long, refresh: Boolean = false): String =
        cached("live-stream:$liveId", LIVE_STREAM_TTL, refresh) { api.liveStream(liveId) }

    // ---- album ----

    suspend fun albumDetail(albumId: Long, refresh: Boolean = false): AlbumDetail? =
        cached("album-detail:$albumId", ALBUM_TTL, refresh) { api.albumDetail(albumId) }

    /** Drop memoised discovery data, e.g. after switching the personalisation preference. */
    suspend fun clearRecommend() {
        synchronized(memo) { memo.keys.filter { it.startsWith("recommend") }.forEach(memo::remove) }
        synchronized(rawMemo) { rawMemo.keys.filter { it.startsWith("recommend") }.forEach(rawMemo::remove) }
        disk?.remove("recommend")
    }

    private class RawDiskCache(private val file: File) {
        private val mutex = Mutex()
        private var loaded = false
        private val values = HashMap<String, Pair<Long, String>>()

        private fun ensure() {
            if (loaded) return
            loaded = true
            runCatching {
                if (!file.exists()) return
                val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                json.keys().forEach { key ->
                    val row = json.optJSONObject(key) ?: return@forEach
                    val at = row.optLong("at")
                    val body = row.optString("body")
                    if (at > 0 && body.isNotBlank()) values[key] = at to body
                }
            }
        }

        private fun persist() {
            val json = JSONObject()
            values.forEach { (name, entry) -> json.put(name, JSONObject().put("at", entry.first).put("body", entry.second)) }
            runCatching {
                file.parentFile?.mkdirs()
                val atomic = AtomicFile(file)
                val out = atomic.startWrite()
                try {
                    out.write(json.toString().toByteArray())
                    atomic.finishWrite(out)
                } catch (failure: Exception) {
                    atomic.failWrite(out)
                    throw failure
                }
            }
        }

        suspend fun read(key: String): Pair<Long, String>? = mutex.withLock { ensure(); values[key] }

        suspend fun remove(prefix: String) = mutex.withLock {
            ensure()
            val removed = values.keys.filter { it.startsWith(prefix) }
            if (removed.isEmpty()) return@withLock
            removed.forEach(values::remove)
            persist()
        }

        suspend fun write(key: String, at: Long, body: String) = mutex.withLock {
            ensure()
            // Refuse to grow the cache without bound; the payloads are large recommendation bodies.
            if (body.length > 2_000_000) return@withLock
            values[key] = at to body
            persist()
        }
    }
}
