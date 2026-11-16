package com.shixi.client

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** One installation's guest batch belongs permanently to its first verified account. */
internal class GuestHistoryBinding(path: File) {
    private val file = AtomicFile(path)
    data class Binding(val owner: String, val complete: Boolean)
    private fun read(): Binding? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        return Binding(json.getString("owner"), json.getBoolean("complete")).also {
            require(it.owner.matches(Regex("account:[1-9][0-9]*")))
        }
    }
    private fun write(binding: Binding) {
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("owner", binding.owner).put("complete", binding.complete).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            check(read() == binding) { "guest history binding was not persisted" }
        } catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    fun claim(owner: String) = synchronized(guard) {
        require(owner.matches(Regex("account:[1-9][0-9]*")))
        if (read() == null) write(Binding(owner, false))
    }
    fun accepted(): Binding? = synchronized(guard) { read() }
    fun complete(owner: String) = synchronized(guard) {
        val existing = checkNotNull(read())
        check(existing.owner == owner)
        write(existing.copy(complete = true))
    }
    companion object { private val guard = Any() }
}

internal class GuestHistoryMerge(path: File, private val source: ListeningDao) {
    private val bindingStore = GuestHistoryBinding(path)
    suspend fun stage(lease: AccountLease, target: CloudDao, current: (AccountLease) -> Boolean, revision: () -> Long) {
        if (!current(lease)) return
        // Ownership must already be durably accepted by AccountManager; sync never elects an owner.
        val binding = checkNotNull(bindingStore.accepted()) { "guest history owner was not accepted" }
        if (binding.owner != lease.owner || binding.complete) return
        val rows = source.history(LOCAL_OWNER)
        val retained = target.history(lease.owner).associateBy { it.id }
        for (row in rows) {
            if (!current(lease)) return
            // Never accept another account's records, even if the UI combines history views.
            if (row.owner != LOCAL_OWNER || row.id <= 0 || row.albumId <= 0 || row.updatedAt <= 0) continue
            val existing = retained[row.id]
            if (row.updatedAt > (existing?.let { maxOf(it.localAt, it.serverAt) } ?: 0)) {
                target.record(CloudHistoryRow(lease.owner, row.id, row.albumId, row.title, row.cover,
                    row.positionMs, row.durationMs, row.completed, row.updatedAt), revision())
            }
        }
        // A crash before this commit replays only rows not already retained at this timestamp.
        if (current(lease)) bindingStore.complete(lease.owner)
    }
}
