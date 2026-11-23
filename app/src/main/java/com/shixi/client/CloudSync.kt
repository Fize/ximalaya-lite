package com.shixi.client

import android.content.Context
import com.shixi.protocol.Item
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Account records stay isolated; the first verified account receives the guest history batch. */
data class CloudState(val owner: String = "", val history: List<CloudHistoryRow> = emptyList(),
    val subscriptions: List<CloudSubscriptionRow> = emptyList(), val pending: Int = 0,
    val busy: Boolean = false, val message: String = "登录后可同步账号历史和订阅", val problem: SyncProblem? = null,
    val refreshing: Boolean = false, val historyMore: Boolean = false, val historyLoading: Boolean = false)

class CloudSync internal constructor(private val account: AccountManager, private val dao: CloudDao,
    private val gateway: CloudGateway, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val guestMerge: GuestHistoryMerge? = null,
    private val retryDelay: (Int) -> Long = ::backoff,
    private val localHistory: suspend (String) -> List<HistoryRow> = { emptyList() }) {
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(CloudState())
    val state = mutable.asStateFlow()
    private var retry: Job? = null
    private var revision = clock()
    private var readAttempts = 0
    private var historyPage = 0
    private var historyMore = false
    private var historyLoading = false
    private val requested = java.util.concurrent.atomic.AtomicBoolean(false)
    init {
        scope.launch { for (task in work) try { task() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(busy = false, message = "账号记录处理失败，原记录保留，请重试", problem = SyncProblem.from("本机记录处理", error)) } }
        scope.launch { var observed: AccountLease? = null
            account.state.collect {
            val lease = account.lease()
            if (observed?.generation == lease?.generation && observed?.session === lease?.session) return@collect
            observed = lease
            // Hide the previous account immediately, before queued I/O or network completes.
            if (mutable.value.owner != lease?.owner.orEmpty()) mutable.value = CloudState(owner = lease?.owner.orEmpty())
            retry?.cancel(); readAttempts = 0; historyPage = 0; historyMore = false; historyLoading = false
            if (lease != null) submit { if (account.current(lease)) { refresh(lease); synchronize(lease, false) } }
        } }
    }
    private fun submit(task: suspend () -> Unit) { check(work.trySend(task).isSuccess) }
    private fun nextRevision(): Long { revision = maxOf(revision + 1, clock()); return revision }
    private suspend fun refresh(lease: AccountLease, message: String? = null, problem: SyncProblem? = null) {
        val histories = dao.history(lease.owner); val subs = dao.subscriptions(lease.owner); val pending = dao.pending(lease.owner)
        if (account.current(lease)) mutable.value = mutable.value.copy(owner = lease.owner, history = histories, subscriptions = subs,
            pending = pending.size, message = message ?: if (pending.isEmpty()) "账号记录已读取，尚未核对服务器" else "${pending.size}项待同步；离线修改尚未由服务器确认",
            problem = problem, historyMore = historyMore, historyLoading = historyLoading)
    }
    fun sync() = requestSync(true)
    fun resume() = requestSync(false)
    private fun requestSync(manual: Boolean) {
        val lease = account.lease() ?: return
        if (!requested.compareAndSet(false, true)) return
        submit { try { synchronize(lease, manual) } finally { requested.set(false) } }
    }
    fun toggle(item: Item, expectedOwner: String? = null) {
        require(item.id > 0)
        val lease = account.lease() ?: return
        if (expectedOwner != null && lease.owner != expectedOwner) return
        submit { if (account.current(lease)) {
            val current = dao.subscriptions(lease.owner).firstOrNull { it.id == item.id }
            dao.subscribe(CloudSubscriptionRow(lease.owner, item.id, item.title, item.cover, current?.subscribed != true), nextRevision())
            refresh(lease); synchronize(lease, false)
        } }
    }
    fun record(lease: AccountLease?, row: HistoryRow) {
        val playback = lease?.let(account::renewPlayback) ?: return
        submit { account.renewPlayback(playback)?.let { lease ->
            val previous = dao.history(lease.owner).firstOrNull { it.id == row.id }
            if (previous != null && (row.updatedAt < maxOf(previous.localAt, previous.serverAt) ||
                row.updatedAt == previous.serverAt && previous.serverAt > 0 ||
                row.updatedAt == previous.localAt && previous.positionMs == row.positionMs && previous.completed == row.completed)) return@let
            dao.record(CloudHistoryRow(lease.owner, row.id, row.albumId, row.title, row.cover,
                row.positionMs, row.durationMs, row.completed, row.updatedAt), nextRevision())
            refresh(lease)
            val pending = dao.pending(lease.owner).firstOrNull { it.kind == "history" && it.id == row.id } ?: return@let
            // Keep one cadence instead of postponing the upload on every ten-second heartbeat.
            if (retry?.isActive != true && pending.attempts < 5 && pending.nextAt != Long.MAX_VALUE)
                schedule(lease, maxOf(30_000, pending.nextAt - clock()))
        } }
    }
    private fun schedule(lease: AccountLease, delayMs: Long) {
        retry?.cancel(); retry = scope.launch { delay(delayMs.coerceAtLeast(1_000)); submit { synchronize(lease, false) } }
    }
    fun loadMoreHistory() {
        val lease = account.lease() ?: return
        if (historyLoading || !historyMore) return
        historyLoading = true
        mutable.value = mutable.value.copy(historyLoading = true)
        submit {
            try {
                if (!account.current(lease) || !historyMore) return@submit
                val page = historyPage + 1
                val result = gateway.readPage(lease, page)
                if (!account.current(lease)) return@submit
                if (result.snapshot.history.isNotEmpty()) dao.mergeHistory(lease.owner, result.snapshot.history)
                historyPage = page
                historyMore = result.hasMore
                refresh(lease)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (account.current(lease)) mutable.value = mutable.value.copy(message = "历史加载失败，请重试",
                    problem = SyncProblem.from("历史读取", error))
            } finally {
                historyLoading = false
                if (account.current(lease)) mutable.value = mutable.value.copy(historyLoading = false)
            }
        }
    }
    private fun confirmed(op: SyncRow, remote: CloudSnapshot, history: CloudHistoryRow?, sub: CloudSubscriptionRow?): Boolean =
        if (op.kind == "subscription") remote.subscriptionFailure == null && remote.subscriptions.any { it.id == op.id } == sub?.subscribed
        else remote.historyFailure == null && history != null && remote.history.any { it.albumId == history.albumId && it.serverAt > 0 && it.serverAt >= history.localAt }

    /** One page is enough for the normal path. A bounded scan only covers a previously attempted write. */
    private suspend fun readRemote(lease: AccountLease, needed: Set<Long> = emptySet()): CloudSnapshot {
        val history = mutableListOf<CloudHistoryRow>()
        var subscriptions: List<CloudSubscriptionRow> = emptyList()
        var historyFailure: Exception? = null
        var subscriptionFailure: Exception? = null
        var page = 0
        var more = true
        val limit = if (needed.isEmpty()) 1 else 5
        while (more && page < limit) {
            val result = gateway.readPage(lease, page + 1)
            page++
            if (page == 1) {
                subscriptions = result.snapshot.subscriptions
                historyFailure = result.snapshot.historyFailure
                subscriptionFailure = result.snapshot.subscriptionFailure
            }
            if (account.current(lease)) {
                if (result.snapshot.history.isNotEmpty()) dao.mergeHistory(lease.owner, result.snapshot.history)
                history.addAll(result.snapshot.history)
                historyPage = maxOf(historyPage, page)
                historyMore = result.hasMore
                refresh(lease)
                if (account.current(lease)) mutable.value = mutable.value.copy(busy = true)
            }
            more = result.hasMore && historyFailure == null && needed.isNotEmpty() &&
                needed.any { id -> history.none { it.id == id } }
        }
        return CloudSnapshot(history, subscriptions, historyFailure, subscriptionFailure)
    }

    private suspend fun synchronize(lease: AccountLease, manual: Boolean) {
        if (!account.current(lease)) return
        retry?.cancel(); mutable.value = mutable.value.copy(busy = true, refreshing = manual, message = "正在核对服务器…")
        var stage = "本机记录合并"
        var affectedKind: String? = null
        try {
            guestMerge?.stage(lease, dao, account::current, ::nextRevision)
            // A process may die after the player persisted a local event but before this
            // worker got past an in-flight request. Recover that durable event first.
            val retained = dao.history(lease.owner).associateBy { it.id }
            for (row in localHistory(lease.owner)) {
                if (!account.current(lease)) return
                val previous = retained[row.id]
                if (row.owner == lease.owner && row.updatedAt > (previous?.let { maxOf(it.localAt, it.serverAt) } ?: 0))
                    dao.record(CloudHistoryRow(lease.owner, row.id, row.albumId, row.title, row.cover,
                        row.positionMs, row.durationMs, row.completed, row.updatedAt), nextRevision())
            }
            if (!account.current(lease)) return
            // Query before every resend: a previous timeout may have happened after acceptance.
            stage = "账号记录读取"
            // Rows that were never sent need no lookup; only an ambiguous previous attempt does.
            val needed = dao.pending(lease.owner).filter { it.kind == "history" && it.attempts > 0 }.map { it.id }.toSet()
            var remote = readRemote(lease, needed)
            if (!account.current(lease)) return
            dao.reconcile(lease.owner, remote)
            val histories = dao.history(lease.owner).associateBy { it.id }
            val subscriptions = dao.subscriptions(lease.owner).associateBy { it.id }
            val pending = dao.pending(lease.owner)
            // The cloud endpoint stores one listening cursor per album, not one per episode.
            // Keep every episode locally, but send only the newest pending event per album.
            val latest = pending.filter { it.kind == "history" && histories[it.id] != null }
                .groupBy { histories.getValue(it.id).albumId }
                .mapValues { (_, ops) -> ops.maxWith(compareBy<SyncRow> { histories.getValue(it.id).localAt }.thenBy { it.revision }) }
            val sent = mutableListOf<SyncRow>()
            for (op in pending) {
                if (!account.current(lease)) return
                val history = histories[op.id]; val sub = subscriptions[op.id]
                if (op.kind == "history" && remote.historyFailure != null || op.kind == "subscription" && remote.subscriptionFailure != null) continue
                if (confirmed(op, remote, history, sub)) { dao.acknowledge(op.owner, op.kind, op.id, op.revision); continue }
                if (op.kind == "history" && history != null && latest[history.albumId]?.id != op.id) continue
                if (!manual && (op.attempts >= 5 || op.nextAt > clock())) continue
                stage = if (op.kind == "history") "进度上传" else "订阅更新"
                affectedKind = op.kind
                if (op.kind == "history" && history != null) gateway.progress(lease, history)
                else if (op.kind == "subscription" && sub != null) gateway.subscription(lease, sub)
                else continue
                sent.add(op)
            }
            if (sent.isNotEmpty()) {
                if (!account.current(lease)) return
                stage = "同步结果确认"
                affectedKind = null
                remote = readRemote(lease, sent.filter { it.kind == "history" }.map { it.id }.toSet())
                if (!account.current(lease)) return
                val sentAlbums = sent.filter { it.kind == "history" }.mapNotNull { histories[it.id]?.albumId }.toSet()
                for (op in pending) {
                    if (confirmed(op, remote, histories[op.id], subscriptions[op.id])) dao.acknowledge(op.owner, op.kind, op.id, op.revision)
                    else if ((op.kind == "history" && remote.historyFailure == null || op.kind == "subscription" && remote.subscriptionFailure == null) &&
                        (op in sent || op.kind == "history" && histories[op.id]?.albumId in sentAlbums))
                        dao.defer(op.owner, op.kind, op.id, op.revision, op.attempts + 1, clock() + backoff(op.attempts + 1))
                }
                dao.reconcile(lease.owner, remote)
            }
            if (!account.current(lease)) return
            dao.reconcile(lease.owner, remote)
            if (remote.historyFailure != null || remote.subscriptionFailure != null) {
                affectedKind = when {
                    remote.historyFailure == null -> "subscription"
                    remote.subscriptionFailure == null -> "history"
                    else -> null
                }
                throw (remote.historyFailure ?: remote.subscriptionFailure)!!
            }
            readAttempts = 0
            val remaining = dao.pending(lease.owner)
            refresh(lease, if (remaining.isEmpty()) "服务器核对完成" else "${remaining.size}项尚未由服务器确认，可稍后重试")
            remaining.filter { it.attempts < 5 }.minOfOrNull { (it.nextAt - clock()).coerceAtLeast(30_000) }?.let { schedule(lease, it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!account.current(lease)) return
            if (error is CloudFailure && error.expired) { account.invalidate(lease); mutable.value = CloudState(message = "登录已失效，请重新登录；待同步记录仍属于原账号") }
            else {
                val pending = dao.pending(lease.owner)
                val retryable = error !is CloudFailure || error.retryable
                for (op in pending.filter { affectedKind == null || it.kind == affectedKind }) dao.defer(op.owner, op.kind, op.id, op.revision, op.attempts + 1, (if (!retryable) Long.MAX_VALUE else clock() + backoff(op.attempts + 1)))
                refresh(lease, if (error is CloudFailure) error.message else "网络不可用或同步失败，原账号待同步记录已保留", SyncProblem.from(stage, error))
                // Business rejections require user action; do not repeatedly replay risk checks.
                if (retryable) {
                    readAttempts++
                    if (readAttempts < 5) schedule(lease, retryDelay(readAttempts))
                }
            }
        } finally { if (account.current(lease)) mutable.value = mutable.value.copy(busy = false, refreshing = false) }
    }
    suspend fun awaitIdle() { val done = CompletableDeferred<Unit>(); submit { done.complete(Unit) }; done.await() }
    fun close() { scope.cancel() }
    companion object {
        private fun backoff(attempt: Int): Long = (30_000L shl attempt.coerceIn(0, 6)).coerceAtMost(1_800_000)
        @Volatile private var instance: CloudSync? = null
        fun get(context: Context): CloudSync = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                val database = ListeningDatabase.open(app)
                CloudSync(AccountManager.get(app), database.cloud(), CloudApi(app),
                    guestMerge = GuestHistoryMerge(java.io.File(app.noBackupFilesDir, "first-account-history-v1"), database.records()),
                    localHistory = { owner -> database.records().history(owner) })
            }.also { instance = it }
        }
    }
}
