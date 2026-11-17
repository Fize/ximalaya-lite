package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Production Room, account observer, merge and HTTP parser; synthetic identities only. */
class FirstLoginSyncVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private class Sessions : SessionStore {
        var value: AccountSession? = null
        override fun read() = value
        override fun write(session: AccountSession) { value = session }
        override fun clear() { value = null }
    }
    private class Login : AccountGateway {
        var uid = 7301L
        override fun send(mobile: String) { error("no real SMS") }
        override fun login(mobile: String, code: String) = AccountSession(uid, "synthetic")
        override fun check(session: AccountSession) = true
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < end) Thread.sleep(20)
        check(condition()) { label }
    }
    override fun onStart() {
        val result = Bundle(); val passed = arrayListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dbFile = File(targetContext.noBackupFilesDir, "first-login-sync-verification.db"); dbFile.delete()
        val db = ListeningDatabase.open(targetContext, dbFile.name)
        var sync: CloudSync? = null
        var fixtureAccount: AccountManager? = null
        var activity: android.app.Activity? = null
        val accountField = AccountManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val syncField = CloudSync::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val oldAccount = accountField.get(null); val oldSync = syncField.get(null)
        fun passed(name: String) { passed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        try {
            var now = 1000L
            val events = ListeningEvents { now }
            val base = HistoryRow("account:7301", 1, 1, 1, "fixture", "", 2000, 60000, false, 0)
            val first = events.stamp(base); now = 2000
            check(events.stamp(base).updatedAt == first.updatedAt)
            val replay = events.stamp(base, explicit = true); check(replay.updatedAt == 2000L)
            now = 3000; check(events.stamp(base.copy(positionMs = 1000)).updatedAt == 3000L)
            now = 4000; check(events.stamp(base.copy(positionMs = 1000, completed = true)).updatedAt == 4000L)
            passed("only_listening_and_seek_advance_event_time")
            val login = Login()
            val manager = AccountManager(login, Sessions(), targetContext.getSharedPreferences("first-sync-fixture", Context.MODE_PRIVATE), 0, scope)
            fixtureAccount = manager
            waitFor("initial account") { !manager.state.value.busy }
            var reads = 0
            var offline = true
            val owner = "account:7301"
            val latest = CloudHistoryRow(owner, 730111, 7301, "首次登录云端收听记录", "", 42000, 120000, false, 0, 5000)
            var accepted: CloudHistoryRow? = null
            var uploads = 0
            val remote = object : CloudGateway {
                override fun read(lease: AccountLease): CloudSnapshot {
                    reads++
                    if (offline) throw java.io.IOException("offline fixture")
                    return CloudSnapshot(if (lease.owner == owner) listOf(accepted ?: latest) else emptyList(), emptyList())
                }
                override fun subscription(lease: AccountLease, row: CloudSubscriptionRow) { error("unexpected write") }
                override fun progress(lease: AccountLease, row: CloudHistoryRow) {
                    check(row.localAt >= 11000) { "old progress must not upload" }
                    uploads++; accepted = row.copy(localAt = 0, serverAt = row.localAt)
                }
            }
            val cloud = CloudSync(manager, db.cloud(), remote, CoroutineScope(SupervisorJob() + Dispatchers.IO), retryDelay = { 1000 })
            sync = cloud
            accountField.set(null, manager); syncField.set(null, cloud)
            check(AccountManager.get(targetContext) === manager && CloudSync.get(targetContext) === cloud) { "singleton injection failed" }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            manager.login("synthetic", "synthetic")
            waitFor("first read failure without pending uploads") { reads > 0 && cloud.state.value.problem != null && !cloud.state.value.busy }
            check(cloud.state.value.pending == 0)
            offline = false
            waitFor("automatic retry with empty outbox") { cloud.state.value.history.any { it.id == latest.id } && !cloud.state.value.busy }
            passed("first_login_empty_outbox_automatic_retry")
            waitFor("verified fixture account visible") { manager.state.value.signedIn && !manager.state.value.busy }
            runOnMainSync { check(AccountManager.get(targetContext) === manager && CloudSync.get(targetContext) === cloud) { "UI singleton differs" } }
            fun nodes(text: String): List<AccessibilityNodeInfo> {
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                val found = mutableListOf<AccessibilityNodeInfo>()
                fun walk(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node)
                    repeat(node.childCount) { walk(node.getChild(it)) }
                }
                walk(uiAutomation.rootInActiveWindow)
                return found
            }
            fun click(text: String) {
                nodes(text).forEach { node ->
                    var candidate: AccessibilityNodeInfo? = node
                    while (candidate != null) {
                        if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                        candidate = candidate.parent
                    }
                }
            }
            waitFor("home receives cloud history after login") {
                click("Don't show again"); click("Don't Show Again"); click("OK"); click("确定")
                nodes("首次登录云端收听记录").isNotEmpty()
            }
            click("我的"); waitFor("library navigation") { nodes("历史").isNotEmpty() }; click("历史")
            waitFor("history page uses same cloud record") { nodes("首次登录云端收听记录").isNotEmpty() }
            passed("home_and_history_observe_login_download_without_refresh")
            click("收听"); waitFor("cloud continue card") { nodes("继续收听").isNotEmpty() && nodes(latest.title).isNotEmpty() }
            runOnMainSync {
                val service = checkNotNull(PlaybackService.active)
                @Suppress("UNCHECKED_CAST")
                val heard = service.javaClass.getDeclaredField("heard").apply { isAccessible = true }.get(service) as MutableSet<Long>
                heard.add(latest.id)
            }
            click(latest.title)
            waitFor("cloud title opens correct player") { nodes("正在播放").isNotEmpty() }
            val controllerFuture = androidx.media3.session.MediaController.Builder(targetContext,
                androidx.media3.session.SessionToken(targetContext, android.content.ComponentName(targetContext, PlaybackService::class.java))).buildAsync()
            val controller = controllerFuture.get(10, TimeUnit.SECONDS)
            runOnMainSync {
                check(controller.currentMediaItem?.let(PlaybackItems::item)?.id == latest.id)
                check(controller.currentPosition == latest.positionMs && !controller.playWhenReady)
                controller.release()
            }
            runBlocking { cloud.awaitIdle() }
            check(cloud.state.value.history.first { it.id == latest.id }.let { it.serverAt == 5000L && it.localAt == 0L })
            check(cloud.state.value.pending == 0)
            click("收起")
            passed("cloud_continue_cover_opens_correct_episode_and_breakpoint_without_autoplay")
            runBlocking {
                val dao = db.cloud()
                dao.record(latest.copy(positionMs = 90000, localAt = 4000, serverAt = 0), 1)
                check(dao.history(owner).single().positionMs == 42000L)
                dao.record(latest.copy(positionMs = 90000, localAt = 5000, serverAt = 0), 2)
                check(dao.pending(owner).isEmpty())
                dao.record(latest.copy(positionMs = 1000, localAt = 6000, serverAt = 0), 3)
                dao.reconcile(owner, CloudSnapshot(listOf(latest), emptyList()))
                check(dao.history(owner).single().positionMs == 1000L)
                dao.mergeHistory(owner, listOf(latest.copy(positionMs = 3000, serverAt = 7000), latest.copy(positionMs = 2000, serverAt = 6500)))
                check(dao.history(owner).single().positionMs == 3000L && dao.pending(owner).isEmpty())
                dao.mergeHistory(owner, listOf(latest.copy(positionMs = 0, serverAt = 0)))
                check(dao.history(owner).single().positionMs == 3000L)
                dao.record(latest.copy(positionMs = 4000, localAt = 8000, serverAt = 0), 4)
                dao.record(latest.copy(positionMs = 5000, localAt = 9000, serverAt = 0), 5)
                dao.acknowledge(owner, "history", latest.id, 4)
                check(dao.pending(owner).single().revision == 5L)
            }
            passed("latest_event_wins_rewind_ties_duplicate_missing_time_and_stale_ack")
            cloud.close(); runOnMainSync { activity?.finish() }; activity = null
            // Actual HTTP parser: duplicate across pages + subscriptions failure cannot hide history.
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val history = request.url.encodedPath.contains("history/query")
                val page = request.url.queryParameter("pageId")?.toInt() ?: 1
                val json = if (history) JSONObject().put("ret", 0).put("data", JSONObject().put("listenModels", JSONArray().apply {
                    val count = if (page == 1) 20 else 1
                    repeat(count) { i -> put(JSONObject().put("type",1).put("itemId",7301).put("childId",if (page==1) 80000+i else 80000)
                        .put("endedAt",if(page==1) 1000 else 2000).put("breakSecond",if(page==1) 30 else 5).put("length",60).put("childTitle","parser fixture")) }
                })).toString() else "{}"
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(history) 200 else 500).message("fixture").body(json.toResponseBody()).build()
            }.build()
            val api = CloudApi(targetContext, http, current = manager::current)
            runBlocking {
                var published = false
                val snapshot = api.readIndependent(manager.lease()!!) { rows ->
                    published = true; check(rows.size == 20 && rows.first { it.id == 80000L }.let { it.serverAt == 2000L && it.positionMs == 5000L })
                    db.cloud().mergeHistory(owner, rows)
                }
                check(published && snapshot.historyFailure == null && snapshot.subscriptionFailure != null)
                db.cloud().putSubscription(CloudSubscriptionRow(owner, 20, "retained subscription", "", true, true))
                db.cloud().reconcile(owner, snapshot)
                check(db.cloud().subscriptions(owner).single().subscribed)
            }
            passed("production_parser_latest_duplicate_and_independent_subscription_failure")
            // New sync instance retries pending initial downloads on process-equivalent recreation.
            runBlocking { db.cloud().mergeHistory(owner, listOf(latest.copy(serverAt = 10000))) }
            offline = true
            var restarted = CloudSync(manager, db.cloud(), remote, CoroutineScope(SupervisorJob() + Dispatchers.IO), retryDelay = { 1000 }); sync = restarted
            waitFor("recreated sync failed read") { restarted.state.value.problem != null && !restarted.state.value.busy }
            offline = false; restarted.resume()
            waitFor("network recovery triggers read") { restarted.state.value.problem == null && !restarted.state.value.busy }
            restarted.close()
            runBlocking { db.records().putHistory(latest.history().copy(positionMs = 8000, updatedAt = 11000)) }
            restarted = CloudSync(manager, db.cloud(), remote, CoroutineScope(SupervisorJob() + Dispatchers.IO),
                retryDelay = { 1000 }, localHistory = { accountOwner -> db.records().history(accountOwner) })
            sync = restarted
            waitFor("durable local event restored and uploaded") { uploads == 1 && restarted.state.value.pending == 0 && restarted.state.value.history.firstOrNull { it.id == latest.id }?.serverAt == 11000L && !restarted.state.value.busy }
            restarted.resume(); runBlocking { restarted.awaitIdle() }; check(uploads == 1)
            passed("local_account_event_recovered_after_process_recreation_without_duplicate_upload")
            manager.logout(); login.uid = 7302; manager.login("synthetic", "synthetic")
            waitFor("account switch owns empty history") { restarted.state.value.owner == "account:7302" && !restarted.state.value.busy && restarted.state.value.message == "服务器核对完成" }
            check(restarted.state.value.history.isEmpty())
            passed("recreation_resume_and_account_isolation")
            result.putString("result", "PASS"); result.putStringArrayList("tests", passed); finish(-1, result)
        } catch (error: Throwable) {
            runCatching {
                result.putString("accountDiagnostic", "same=${AccountManager.get(targetContext) === fixtureAccount}, signedIn=${fixtureAccount?.state?.value?.signedIn}, mainSignedIn=${AccountManager.get(targetContext).state.value.signedIn}, loader=${AccountManager::class.java.classLoader}")
                result.putString("cloudDiagnostic", "same=${CloudSync.get(targetContext) === sync}, count=${sync?.state?.value?.history?.size}, mainCount=${CloudSync.get(targetContext).state.value.history.size}, busy=${sync?.state?.value?.busy}")
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                val lines = mutableListOf<String>()
                fun walk(node: AccessibilityNodeInfo?, depth: Int = 0) {
                    if (node == null || depth > 30) return
                    lines.add("${node.className}: ${node.text?.toString().orEmpty()} / ${node.contentDescription?.toString().orEmpty()}")
                    repeat(node.childCount) { walk(node.getChild(it), depth + 1) }
                }
                walk(uiAutomation.rootInActiveWindow)
                val dump = lines.joinToString("\n")
                File(targetContext.cacheDir, "first-sync-failure.txt").writeText(dump)
                result.putString("visible", dump.take(6000))
                uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.cacheDir, "first-sync-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            }
            result.putString("failure", error.javaClass.simpleName + ": " + error.message); result.putStringArrayList("tests", passed); finish(0, result)
        } finally {
            runOnMainSync { activity?.finish() }
            accountField.set(null, oldAccount); syncField.set(null, oldSync)
            sync?.close(); scope.cancel(); db.close()
        }
    }
}
