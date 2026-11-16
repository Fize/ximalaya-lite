package com.shixi.client

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.*
import com.shixi.protocol.Item
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

// Only local/guest records exist in this phase. Account cloud data is never read or written.
const val LOCAL_OWNER = "guest"
@Entity(tableName = "queue", primaryKeys = ["owner", "ordinal"])
data class QueueRow(val owner: String = LOCAL_OWNER, val ordinal: Int, val id: Long, val albumId: Long,
    val page: Int, val title: String, val cover: String) {
    fun item() = Item(id, title, false, albumId, cover, page)
}
@Entity(tableName = "cursor", primaryKeys = ["owner"])
data class CursorRow(val owner: String = LOCAL_OWNER, val index: Int, val positionMs: Long)
@Entity(tableName = "history", primaryKeys = ["owner", "id"])
data class HistoryRow(val owner: String = LOCAL_OWNER, val id: Long, val albumId: Long, val page: Int,
    val title: String, val cover: String, val positionMs: Long, val durationMs: Long,
    val completed: Boolean, val updatedAt: Long) {
    fun item() = Item(id, title, false, albumId, cover, page)
    val resumeMs: Long get() = if (completed) 0 else positionMs
}
@Entity(tableName = "following", primaryKeys = ["owner", "id"])
data class FollowRow(val owner: String = LOCAL_OWNER, val id: Long, val title: String, val cover: String) {
    fun item() = Item(id, title, false, cover = cover)
}
@Entity(tableName = "search_history", primaryKeys = ["owner", "query"])
data class SearchRow(val owner: String = LOCAL_OWNER, val query: String, val updatedAt: Long)

@Dao
abstract class ListeningDao {
    @Query("SELECT * FROM queue WHERE owner = :owner ORDER BY ordinal") abstract suspend fun queue(owner: String = LOCAL_OWNER): List<QueueRow>
    @Query("SELECT * FROM cursor WHERE owner = :owner") abstract suspend fun cursor(owner: String = LOCAL_OWNER): CursorRow?
    @Query("SELECT * FROM history WHERE owner = :owner ORDER BY updatedAt DESC, id DESC") abstract suspend fun history(owner: String = LOCAL_OWNER): List<HistoryRow>
    @Query("SELECT * FROM following WHERE owner = :owner ORDER BY id DESC") abstract suspend fun follows(owner: String = LOCAL_OWNER): List<FollowRow>
    @Query("SELECT * FROM search_history WHERE owner = :owner ORDER BY updatedAt DESC") abstract suspend fun searches(owner: String = LOCAL_OWNER): List<SearchRow>
    @Query("DELETE FROM queue WHERE owner = :owner") abstract suspend fun clearQueue(owner: String = LOCAL_OWNER)
    @Insert abstract suspend fun insertQueue(rows: List<QueueRow>)
    @Upsert abstract suspend fun putCursor(row: CursorRow)
    @Query("SELECT * FROM history WHERE owner = :owner AND id = :id") abstract suspend fun historyItem(id: Long, owner: String = LOCAL_OWNER): HistoryRow?
    @Upsert abstract suspend fun upsertHistory(row: HistoryRow)
    @Transaction open suspend fun putHistory(row: HistoryRow) {
        val previous = historyItem(row.id, row.owner)
        // Preparing/stopping at the saved end is not another unfinished listen.
        // A seek/replay away from the end clears completion in FIFO order.
        val stillAtEnd = previous?.completed == true && row.positionMs >= previous.positionMs - 1000
        upsertHistory(row.copy(completed = row.completed || stillAtEnd))
    }
    @Upsert abstract suspend fun putFollow(row: FollowRow)
    @Query("DELETE FROM following WHERE owner = :owner AND id = :id") abstract suspend fun removeFollow(id: Long, owner: String = LOCAL_OWNER)
    @Upsert abstract suspend fun putSearch(row: SearchRow)
    @Query("DELETE FROM search_history WHERE owner = :owner AND query = :query") abstract suspend fun removeSearch(query: String, owner: String = LOCAL_OWNER)
    @Transaction open suspend fun save(snapshot: PlaybackSnapshot, history: HistoryRow?) {
        clearQueue()
        insertQueue(snapshot.queue.mapIndexed { index, item -> QueueRow(ordinal = index, id = item.id,
            albumId = item.albumId, page = item.page, title = item.title, cover = item.cover) })
        putCursor(CursorRow(index = if (snapshot.queue.isEmpty()) 0 else snapshot.index.coerceIn(snapshot.queue.indices), positionMs = snapshot.positionMs.coerceAtLeast(0)))
        if (history != null) putHistory(history)
    }
}
@Database(entities = [QueueRow::class, CursorRow::class, HistoryRow::class, FollowRow::class, SearchRow::class, CloudHistoryRow::class, CloudSubscriptionRow::class, SyncRow::class], version = 2, exportSchema = true)
abstract class ListeningDatabase : RoomDatabase() {
    abstract fun records(): ListeningDao
    abstract fun cloud(): CloudDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `cloud_history` (`owner` TEXT NOT NULL, `id` INTEGER NOT NULL, `albumId` INTEGER NOT NULL, `title` TEXT NOT NULL, `cover` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `localAt` INTEGER NOT NULL, `serverAt` INTEGER NOT NULL, PRIMARY KEY(`owner`, `id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cloud_subscriptions` (`owner` TEXT NOT NULL, `id` INTEGER NOT NULL, `title` TEXT NOT NULL, `cover` TEXT NOT NULL, `subscribed` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, PRIMARY KEY(`owner`, `id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`owner` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `nextAt` INTEGER NOT NULL, PRIMARY KEY(`owner`, `kind`, `id`))")
            }
        }
        fun open(context: Context, name: String = "listening.db"): ListeningDatabase = Room.databaseBuilder(context.applicationContext,
            ListeningDatabase::class.java, File(context.noBackupFilesDir, name).absolutePath).addMigrations(MIGRATION_1_2).build()
    }
}
data class StoredPlayback(val queue: List<Item>, val index: Int, val positionMs: Long, val speed: Float)
data class ListeningData(val history: List<HistoryRow> = emptyList(), val follows: List<Item> = emptyList(),
    val searches: List<String> = emptyList(), val loaded: Boolean = false, val issue: String = "", val savedRevision: Long = 0)

/** One application-scoped FIFO writer. Accepted old-service writes precede a new service's read.
 * The epoch rejects later callbacks from a disposed player; reads never replace a user's new queue.
 * No destructive migration/corruption fallback: retain records and report the failure instead. */
class ListeningStore private constructor(context: Context) {
    private val db = ListeningDatabase.open(context)
    private val dao = db.records()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = PreferenceDataStoreFactory.create(scope = scope) {
        File(context.noBackupFilesDir, "listening.preferences_pb")
    }
    private val speedKey = floatPreferencesKey("speed")
    private var lastSpeed = 1f
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(ListeningData())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    init {
        scope.launch { for (task in work) { try { task() } catch (_: Exception) {
            mutable.value = mutable.value.copy(issue = "本机记录保存或读取失败，请重试；原记录已保留")
        } } }
        submit { refresh() }
    }
    private fun submit(task: suspend () -> Unit) { check(work.trySend(task).isSuccess) }
    private suspend fun refresh() {
        mutable.value = mutable.value.copy(history = dao.history(), follows = dao.follows().map { it.item() },
            searches = dao.searches().map { it.query }, loaded = true, issue = "")
    }
    @Synchronized fun attachPlayer(): Long = ++epoch
    fun restore(): Deferred<StoredPlayback> {
        val result = CompletableDeferred<StoredPlayback>()
        submit { try {
            val rows = dao.queue(); val cursor = dao.cursor()
            lastSpeed = (preferences.data.first()[speedKey] ?: 1f).takeIf { it.isFinite() && it in 0.5f..3f } ?: 1f
            refresh()
            result.complete(StoredPlayback(rows.map { it.item() }, if (rows.isEmpty()) 0 else (cursor?.index ?: 0).coerceIn(rows.indices), cursor?.positionMs?.coerceAtLeast(0) ?: 0, lastSpeed))
        } catch (error: Exception) { result.completeExceptionally(error); throw error } }
        return result
    }
    @Synchronized fun save(ownerEpoch: Long, snapshot: PlaybackSnapshot, history: HistoryRow?, revision: Long) {
        if (ownerEpoch != epoch) return
        submit {
            dao.save(snapshot, history)
            if (snapshot.speed != lastSpeed) {
                preferences.edit { it[speedKey] = snapshot.speed }; lastSpeed = snapshot.speed
            }
            refresh()
            mutable.value = mutable.value.copy(savedRevision = revision)
        }
    }
    @Synchronized fun history(ownerEpoch: Long, row: HistoryRow) {
        if (ownerEpoch != epoch) return
        submit { dao.putHistory(row); refresh() }
    }
    fun toggleFollow(item: Item) = submit {
        if (dao.follows().any { it.id == item.id }) dao.removeFollow(item.id)
        else dao.putFollow(FollowRow(id = item.id, title = item.title, cover = item.cover))
        refresh()
    }
    fun search(query: String) { val clean = query.trim(); if (clean.isNotEmpty()) submit { dao.putSearch(SearchRow(query = clean, updatedAt = System.currentTimeMillis())); refresh() } }
    fun removeSearch(query: String) = submit { dao.removeSearch(query); refresh() }
    suspend fun awaitIdle() { val done = CompletableDeferred<Unit>(); submit { done.complete(Unit) }; done.await() }
    companion object {
        @Volatile private var instance: ListeningStore? = null
        fun get(context: Context): ListeningStore = instance ?: synchronized(this) {
            instance ?: ListeningStore(context.applicationContext).also { instance = it }
        }
    }
}
