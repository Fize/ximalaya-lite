package com.shixi.client

import android.content.Context
import android.util.AtomicFile
import com.shixi.protocol.ContentApi
import com.shixi.protocol.Item
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Public search metadata only. No identity, signed media URL or listening state is cached here. */
data class DiscoveryPage(val pages: List<List<Item>> = emptyList(), val more: Boolean = false,
    val busy: Boolean = false, val refreshing: Boolean = false, val error: String = "", val failedPage: Int = 1) {
    val loaded get() = pages.isNotEmpty() // A successful empty response is also cached.
    val rows get() = pages.flatten().distinctBy { it.id }
}
class DiscoveryStore internal constructor(private val file: File,
    private val search: suspend (String, Int) -> List<Item>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    companion object {
        val topics = listOf("历史", "人文", "科学", "文学", "儿童", "英语")
        @Volatile private var instance: DiscoveryStore? = null
        fun get(context: Context): DiscoveryStore = instance ?: synchronized(this) {
            instance ?: DiscoveryStore(File(context.applicationContext.filesDir, "discovery-cache-v1.json"),
                { topic, page -> withContext(Dispatchers.IO) { ContentApi().search(topic, page) } }).also { instance = it }
        }
    }
    private val mutable = MutableStateFlow<Map<String, DiscoveryPage>>(emptyMap())
    val state = mutable.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Int>()
    private val writes = Mutex()
    private val hydrated = scope.async { val restored = withContext(Dispatchers.IO) { read() }; mutable.value = restored }
    fun ensure(topic: String) = load(topic, 0)
    fun refresh(topic: String) = load(topic, 1)
    fun next(topic: String) = load(topic, 2)
    private fun load(topic: String, mode: Int) { require(topic in topics)
        scope.launch {
            hydrated.await()
            val old = mutable.value[topic] ?: DiscoveryPage()
            if (mode == 0 && (old.loaded || old.busy || old.error.isNotEmpty())) return@launch
            if (mode == 2 && (!old.loaded || !old.more || old.busy)) return@launch
            if (mode == 1 && old.refreshing) return@launch
            jobs[topic]?.cancel()
            val generation = (generations[topic] ?: 0) + 1; generations[topic] = generation
            val target = if (mode == 2) old.pages.size + 1 else 1
            mutable.value = mutable.value + (topic to old.copy(busy = true, refreshing = mode == 1, error = ""))
            jobs[topic] = scope.launch {
                try {
                    val rows = search(topic, target)
                    ensureActive()
                    if (generations[topic] != generation) return@launch
                    mutable.value = mutable.value + (topic to DiscoveryPage(if (mode == 2) old.pages + listOf(rows) else listOf(rows), rows.size == 20 && (mode != 2 || rows.any { row -> old.rows.none { it.id == row.id } })))
                    persist()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (generations[topic] == generation) mutable.value = mutable.value + (topic to old.copy(failedPage = target, error = if (old.loaded) "更新失败，已保留上次内容。请检查网络后重试。" else "暂时无法加载专辑，请检查网络后重试。"))
                } finally {
                    if (generations[topic] == generation) { val current = mutable.value[topic] ?: old
                        mutable.value = mutable.value + (topic to current.copy(busy = false, refreshing = false)); jobs.remove(topic) }
                }
            }
        }
    }
    private suspend fun persist() {
        // Keep the latest state at lock acquisition, so older disk work cannot overwrite newer results.
        writes.withLock {
            val json = JSONObject()
            mutable.value.filterValues { it.loaded }.forEach { (topic, value) ->
                val pages = JSONArray()
                value.pages.take(10).forEach { page -> val rows = JSONArray(); page.forEach { item -> rows.put(JSONObject().put("id", item.id).put("title", item.title).put("cover", item.cover)) }; pages.put(rows) }
                json.put(topic, JSONObject().put("pages", pages).put("more", value.more || value.pages.size > 10))
            }
            // Cache I/O failure must not turn a successful fetch into a network error or lose visible rows.
            withContext(Dispatchers.IO) { runCatching {
                file.parentFile?.mkdirs(); val atomic = AtomicFile(file); val out = atomic.startWrite()
                try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
            } }
        }
    }
    private fun read(): Map<String, DiscoveryPage> = runCatching {
        if (file.length() > 3_000_000) return emptyMap()
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        topics.mapNotNull { topic -> val cached = json.optJSONObject(topic) ?: return@mapNotNull null
            val stored = cached.getJSONArray("pages"); require(stored.length() in 1..10)
            val pages = (0 until stored.length()).map { p -> val rows = stored.getJSONArray(p); require(rows.length() <= 20)
                (0 until rows.length()).map { i -> val row = rows.getJSONObject(i); Item(row.getLong("id"), row.getString("title"), cover = row.optString("cover")) }.also { rows -> require(rows.all { it.id > 0 && it.title.isNotBlank() }) } }
            topic to DiscoveryPage(pages, cached.optBoolean("more"))
        }.toMap()
    }.getOrDefault(emptyMap())
}
