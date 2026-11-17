package com.shixi.client

/** A UI/player snapshot is not a new listening event. */
internal class ListeningEvents(private val clock: () -> Long = System::currentTimeMillis) {
    private val previous = mutableMapOf<Pair<String, Long>, HistoryRow>()
    fun stamp(row: HistoryRow, explicit: Boolean = false): HistoryRow {
        val key = row.owner to row.id
        val old = previous[key]
        val changed = explicit || old == null || old.positionMs != row.positionMs || old.completed != row.completed
        val result = row.copy(updatedAt = if (changed) maxOf(clock(), (old?.updatedAt ?: 0) + 1) else old!!.updatedAt)
        previous[key] = result
        return result
    }
}
