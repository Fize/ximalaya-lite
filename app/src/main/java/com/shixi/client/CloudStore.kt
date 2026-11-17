package com.shixi.client

import androidx.room.*

@Entity(tableName = "cloud_history", primaryKeys = ["owner", "id"])
data class CloudHistoryRow(val owner: String, val id: Long, val albumId: Long, val title: String,
    val cover: String, val positionMs: Long, val durationMs: Long, val completed: Boolean,
    val localAt: Long, val serverAt: Long = 0) {
    fun history() = HistoryRow(owner, id, albumId, 1, title, cover, positionMs, durationMs, completed, maxOf(localAt, serverAt))
}
@Entity(tableName = "cloud_subscriptions", primaryKeys = ["owner", "id"])
data class CloudSubscriptionRow(val owner: String, val id: Long, val title: String, val cover: String,
    val subscribed: Boolean, val confirmed: Boolean = false) {
    fun item() = com.shixi.protocol.Item(id, title, cover = cover)
}
@Entity(tableName = "sync_outbox", primaryKeys = ["owner", "kind", "id"])
data class SyncRow(val owner: String, val kind: String, val id: Long, val revision: Long,
    val attempts: Int = 0, val nextAt: Long = 0)

@Dao
abstract class CloudDao {
    @Query("SELECT * FROM cloud_history WHERE owner=:owner ORDER BY max(localAt,serverAt) DESC, serverAt DESC, id DESC") abstract suspend fun history(owner: String): List<CloudHistoryRow>
    @Query("SELECT * FROM cloud_subscriptions WHERE owner=:owner ORDER BY id DESC") abstract suspend fun subscriptions(owner: String): List<CloudSubscriptionRow>
    @Query("SELECT * FROM sync_outbox WHERE owner=:owner ORDER BY revision") abstract suspend fun pending(owner: String): List<SyncRow>
    @Upsert abstract suspend fun putHistory(row: CloudHistoryRow)
    @Upsert abstract suspend fun putSubscription(row: CloudSubscriptionRow)
    @Upsert abstract suspend fun enqueue(row: SyncRow)
    @Query("DELETE FROM sync_outbox WHERE owner=:owner AND kind=:kind AND id=:id AND revision=:revision") abstract suspend fun acknowledge(owner: String, kind: String, id: Long, revision: Long): Int
    @Query("UPDATE sync_outbox SET attempts=:attempts,nextAt=:nextAt WHERE owner=:owner AND kind=:kind AND id=:id AND revision=:revision") abstract suspend fun defer(owner: String, kind: String, id: Long, revision: Long, attempts: Int, nextAt: Long)
    @Transaction open suspend fun record(row: CloudHistoryRow, revision: Long) {
        val existing = history(row.owner).firstOrNull { it.id == row.id }
        if (row.localAt <= 0 || existing != null && (row.localAt < maxOf(existing.localAt, existing.serverAt) ||
                row.localAt == existing.serverAt && existing.serverAt > 0)) return
        val previous = pending(row.owner).firstOrNull { it.kind == "history" && it.id == row.id }
        putHistory(row); enqueue(SyncRow(row.owner, "history", row.id, revision, previous?.attempts ?: 0, previous?.nextAt ?: 0))
    }
    @Transaction open suspend fun subscribe(row: CloudSubscriptionRow, revision: Long) {
        putSubscription(row); enqueue(SyncRow(row.owner, "subscription", row.id, revision))
    }
    @Transaction open suspend fun mergeHistory(owner: String, rows: List<CloudHistoryRow>) {
        val outbox = pending(owner)
        val localHistory = history(owner).associateBy { it.id }
        for (row in rows.filter { it.serverAt > 0 }.groupBy { it.id }.values.map { rows -> rows.maxBy { it.serverAt } }) {
            val local = localHistory[row.id]
            // Explicit policy: later event timestamp wins; ties prefer confirmed server data.
            // Pending local writes are retained when newer; never compare playback positions.
            if (local == null || row.serverAt >= maxOf(local.localAt, local.serverAt)) {
                putHistory(row.copy(owner = owner))
                outbox.firstOrNull { it.kind == "history" && it.id == row.id }?.let { acknowledge(owner, it.kind, it.id, it.revision) }
            }
        }
    }
    @Transaction open suspend fun reconcile(owner: String, remote: CloudSnapshot) {
        if (remote.historyFailure == null) mergeHistory(owner, remote.history)
        val outbox = pending(owner)
        if (remote.subscriptionFailure != null) return
        val pendingSubs = outbox.filter { it.kind == "subscription" }.map { it.id }.toSet()
        val remoteIds = remote.subscriptions.map { it.id }.toSet()
        for (local in subscriptions(owner)) {
            if (local.id !in pendingSubs && local.id !in remoteIds) putSubscription(local.copy(subscribed = false, confirmed = true))
        }
        for (row in remote.subscriptions) if (row.id !in pendingSubs) putSubscription(row.copy(owner = owner, subscribed = true, confirmed = true))
    }
}
