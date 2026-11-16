package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.database.sqlite.SQLiteDatabase
import com.shixi.protocol.Item
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic identities only. Room/Keystore/JNI and production sync/parser code run on-device. */
class SyncVerification : Instrumentation() {
    private var phase = "suite"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "suite"; start() }
    private class MemorySession : SessionStore {
        var value: AccountSession? = null
        override fun read() = value
        override fun write(session: AccountSession) { value = session }
        override fun clear() { value = null }
    }
    private class Login : AccountGateway {
        var uid = 101L; var valid = true; var fail = false
        var entered: CountDownLatch? = null; var release: CountDownLatch? = null
        override fun send(mobile: String) { error("SMS prohibited in fixture") }
        override fun login(mobile: String, code: String) = AccountSession(uid, "synthetic-$uid")
        override fun check(session: AccountSession) = if (fail) throw AccountFailure("校验", "模拟断网") else valid
        override fun refresh(session: AccountSession): AccountSession { entered?.countDown(); release?.await(5, TimeUnit.SECONDS); return AccountSession(session.uid, "refreshed-fixture") }
    }
    private class Remote : CloudGateway {
        val histories = mutableMapOf<String, MutableList<CloudHistoryRow>>()
        val subs = mutableMapOf<String, MutableList<CloudSubscriptionRow>>()
        var failWith: Exception? = null
        var fail = false; var failAfterSubscription = false; var rejected = false; var writes = 0
        var entered: CountDownLatch? = null; var release: CountDownLatch? = null
        override fun read(lease: AccountLease): CloudSnapshot {
            entered?.countDown(); release?.await(5, TimeUnit.SECONDS)
            failWith?.let { throw it }
            if (rejected) throw CloudFailure(true, "expired fixture")
            if (fail) throw java.io.IOException("offline fixture")
            return CloudSnapshot(histories[lease.owner].orEmpty().toList(), subs[lease.owner].orEmpty().toList())
        }
        override fun subscription(lease: AccountLease, row: CloudSubscriptionRow) {
            writes++; val list = subs.getOrPut(lease.owner) { mutableListOf() }; list.removeAll { it.id == row.id }
            if (row.subscribed) list.add(row.copy(confirmed = true))
            if (failAfterSubscription) { failAfterSubscription = false; throw java.io.IOException("accepted then timeout fixture") }
        }
        override fun progress(lease: AccountLease, row: CloudHistoryRow) {
            writes++; val list = histories.getOrPut(lease.owner) { mutableListOf() }; list.removeAll { it.id == row.id }
            list.add(row.copy(localAt = 0, serverAt = row.localAt))
        }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        check(condition()) { label }
    }
    private fun runUiVerification() {
        val result = Bundle()
        val accountField = AccountManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val syncField = CloudSync::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val oldAccount = accountField.get(null); val oldSync = syncField.get(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val database = ListeningDatabase.open(targetContext, "sync-ui-verification.db")
        var activity: android.app.Activity? = null
        fun nodes(text: String): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) { if (node == null) return; if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node); for (i in 0 until node.childCount) visit(node.getChild(i)) }
            visit(uiAutomation.rootInActiveWindow); return found
        }
        fun perform(text: String): Boolean {
            for (node in nodes(text)) { var candidate: AccessibilityNodeInfo? = node; while (candidate != null) { if (candidate.isClickable && candidate.isEnabled && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; candidate = candidate.parent } }
            return false
        }
        fun click(text: String) {
            var clicked = false
            waitFor("UI element $text") {
                if (!clicked) {
                    // The system notice can appear asynchronously after the Activity is created.
                    if (text == "我的") {
                        if (nodes("Don't Show Again").isNotEmpty()) perform("Don't Show Again")
                        else if (nodes("OK").isNotEmpty()) perform("OK")
                        else if (nodes("确定").isNotEmpty()) perform("确定")
                    }
                    clicked = perform(text)
                }
                clicked
            }
        }
        try {
            val login = Login().apply { uid = 9001 }; val store = MemorySession()
            val manager = AccountManager(login, store, targetContext.getSharedPreferences("sync-ui-only", Context.MODE_PRIVATE),0,scope)
            waitFor("initial account") { !manager.state.value.busy }
            manager.login("13800000000","123456"); waitFor("login fixture") { manager.state.value.signedIn && !manager.state.value.busy }
            val remote = Remote().apply {
                histories["account:9001"] = mutableListOf(CloudHistoryRow("account:9001",900112,1,"账号历史夹具","",42000,100000,false,0,5000))
                subs["account:9001"] = mutableListOf(CloudSubscriptionRow("account:9001",1001,"账号订阅夹具","",true,true))
            }
            val sync = CloudSync(manager,database.cloud(),remote,scope)
            waitFor("fixture cloud loaded") { sync.state.value.history.isNotEmpty() && !sync.state.value.busy }
            accountField.set(null,manager); syncField.set(null,sync)
            activity = startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Android's supplied legacy SO may show a platform compatibility notice first.
            if (nodes("Don't show again").isNotEmpty()) click("Don't show again")
            if (nodes("OK").isNotEmpty()) click("OK")
            if (nodes("确定").isNotEmpty()) click("确定")
            click("我的"); waitFor("library subscription") { nodes("账号订阅夹具").isNotEmpty() }
            click("历史"); waitFor("cloud history") { nodes("账号历史夹具").isNotEmpty() }
            click("订阅"); waitFor("subscription filter") { nodes("账号订阅夹具").isNotEmpty() }
            click("收听"); waitFor("home rendered") { nodes("继续收听").isNotEmpty() }; click("我的")
            waitFor("filter survives tab switch") { nodes("账号订阅夹具").isNotEmpty() }
            click("设置"); waitFor("account sync settings") { nodes("同步收听记录").isNotEmpty() }
            check(nodes("合并本机历史…").isEmpty() && nodes("导入本机历史").isEmpty() && nodes("导入").isEmpty())
            remote.failWith = CloudFailure(message = "读取账号历史：同步服务 HTTP 500，原记录保留", retryable = true)
            click("同步收听记录"); waitFor("specific sync error visible") { nodes("历史读取失败（HTTP 500）").isNotEmpty() }
            check(sync.state.value.history.isNotEmpty())
            remote.failWith = null
            click("同步收听记录"); waitFor("error clears after actual recovery") { nodes("已同步").isNotEmpty() && sync.state.value.problem == null }
            check(nodes("技术诊断").isEmpty())
            uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.cacheDir,"auto-history-no-import.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            click("返回"); click("历史"); waitFor("history before account switch") { nodes("账号历史夹具").isNotEmpty() }
            val writes = remote.writes
            manager.logout(); waitFor("logout hides account rows") { nodes("账号历史夹具").isEmpty() && nodes("账号订阅夹具").isEmpty() }
            check(remote.writes == writes)
            login.uid=9002; manager.login("13800000000","123456"); waitFor("second account") { manager.state.value.uid==9002L && !manager.state.value.busy }
            waitFor("new empty cloud view") { sync.state.value.owner=="account:9002" && !sync.state.value.busy }
            check(nodes("账号历史夹具").isEmpty() && nodes("账号订阅夹具").isEmpty())
            result.putString("result","PASS ui: actual Activity cloud history/subscriptions/settings sync/no manual import/account-switch hiding")
            finish(-1,result)
        } catch (error: Throwable) { uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.cacheDir,"ui-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }; result.putString("failure",error.javaClass.simpleName+": "+error.message?.take(300)); finish(0,result) }
        finally { activity?.let { runOnMainSync { it.finish() } }; accountField.set(null,oldAccount); syncField.set(null,oldSync); scope.cancel(); database.close() }
    }

    private fun runNaturalServiceVerification() {
        val result = Bundle()
        val accountField = AccountManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val syncField = CloudSync::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousAccount = accountField.get(null); val previousSync = syncField.get(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(targetContext.noBackupFilesDir,"sync-natural-service.db"); file.delete()
        val database = ListeningDatabase.open(targetContext,"sync-natural-service.db")
        var player: androidx.media3.session.MediaController? = null
        fun <T> main(block: () -> T): T {
            var value: T? = null; var error: Throwable? = null
            runOnMainSync { try { value = block() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }; @Suppress("UNCHECKED_CAST") return value as T
        }
        try {
            val login = Login().apply { uid=9501 }
            val manager = AccountManager(login,MemorySession(),targetContext.getSharedPreferences("sync-natural-only",Context.MODE_PRIVATE),0,scope)
            waitFor("account initialized") { !manager.state.value.busy }
            manager.login("13800000000","123456"); waitFor("fixture account verified") { manager.state.value.signedIn && !manager.state.value.busy }
            val remote = Remote().apply { fail = true }
            val sync = CloudSync(manager,database.cloud(),remote,scope)
            accountField.set(null,manager); syncField.set(null,sync)
            val future = main { androidx.media3.session.MediaController.Builder(targetContext,androidx.media3.session.SessionToken(targetContext,android.content.ComponentName(targetContext,PlaybackService::class.java))).buildAsync() }
            val controller = future.get(20,TimeUnit.SECONDS); player = controller
            waitFor("storage ready") { main { controller.sessionExtras.getBoolean("storageReady") } }
            main {
                controller.setMediaItems(listOf(PlaybackItems.mediaItem(Item(222612,"账号自然切集第一集",false,3544)),PlaybackItems.mediaItem(Item(222617,"账号自然切集第二集",false,3544))))
                controller.prepare(); controller.play()
            }
            waitFor("first real audio") { main { controller.isPlaying && controller.currentPosition > 1500 && controller.duration > 10000 } }
            val firstDuration = main { controller.duration }
            result.putLong("initialDurationMs", firstDuration)
            val engine = main {
                val service = checkNotNull(PlaybackService.active)
                service.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(service) as androidx.media3.exoplayer.ExoPlayer
            }
            val rendered = main { engine.audioDecoderCounters?.renderedOutputBufferCount ?: 0 }
            var transitionEnd = -1L
            var finalDuration = -1L
            main { engine.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPositionDiscontinuity(old: androidx.media3.common.Player.PositionInfo, new: androidx.media3.common.Player.PositionInfo, reason: Int) {
                    if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION && old.mediaItem?.mediaId == "222612:3544:1") {
                        transitionEnd = old.positionMs
                        finalDuration = engine.currentTimeline.getWindow(old.mediaItemIndex, androidx.media3.common.Timeline.Window()).durationMs
                    }
                }
            }) }
            check(rendered > 0) { "no rendered audio buffers" }
            if (phase == "service_refresh") {
                val original = manager.lease()!!
                repeat(2) {
                    manager.refresh(); waitFor("verified playback refresh") { !manager.state.value.busy }
                    check(!manager.current(original)); check(manager.renewPlayback(original)?.session === manager.lease()!!.session)
                }
                // A credential replacement intentionally invalidates the old audio stream.
                // Resume through the controller so the next request uses the verified identity.
                waitFor("old credential stream stopped") { main { !controller.isPlaying && controller.playbackState == androidx.media3.common.Player.STATE_IDLE } }
                runBlocking { sync.awaitIdle() }
                val before = runBlocking { database.cloud().history(original.owner).first { it.id==222612L }.localAt }
                main { controller.play() }
                waitFor("playback resumed with refreshed identity") { main { controller.isPlaying } }
                // After resuming, no further play/seek/save command: wait for the real heartbeat.
                waitFor("heartbeat after two token refreshes and resume") { runBlocking {
                    database.cloud().history(original.owner).firstOrNull { it.id==222612L }?.let { it.localAt>before && it.positionMs>8000 } == true
                } }
                runBlocking { check(database.cloud().pending(original.owner).any { it.id==222612L }) }
                result.putString("refreshHeartbeat", "PASS two verified refreshes, old network lease rejected and stream stopped, resumed real heartbeat persisted")
            }
            main { controller.seekTo(firstDuration - 4000) }
            waitFor("natural next episode playing") { main { controller.currentMediaItem?.mediaId == "222617:3544:1" && controller.isPlaying && controller.currentPosition > 1800 } }
            main { controller.pause() }
            val observedEnd = main { transitionEnd to finalDuration }
            check(observedEnd.first > firstDuration - 1000 && observedEnd.second > 0) { "missing final transition duration" }
            result.putString("transition", "position=${observedEnd.first},finalDuration=${observedEnd.second}")
            waitFor("cloud final previous episode and next breakpoint") { runBlocking {
                val rows = database.cloud().history("account:9501")
                rows.firstOrNull { it.id==222612L }?.let { it.completed && it.positionMs == observedEnd.first && it.durationMs == observedEnd.second } == true &&
                    rows.firstOrNull { it.id==222617L }?.let { !it.completed && it.positionMs in 1500..10000 } == true
            } }
            runBlocking {
                val dao=database.cloud(); val pending=dao.pending("account:9501")
                check(pending.any { it.kind=="history" && it.id==222612L })
                check(pending.any { it.kind=="history" && it.id==222617L })
                check(dao.history("account:9502").isEmpty() && dao.pending("account:9502").isEmpty())
                val rows=dao.history("account:9501")
                result.putString("breakpoints",rows.joinToString { "${it.id}:${it.positionMs}/${it.durationMs}:completed=${it.completed}" })
            }
            // The initial read failure must retain both episodes. Recover and require
            // independent remote readback before either outbox entry is acknowledged.
            remote.fail = false
            sync.sync()
            runBlocking { sync.awaitIdle() }
            runBlocking {
                check(database.cloud().pending("account:9501").isEmpty()) { "playback outbox not acknowledged after recovery" }
                val uploaded = remote.read(manager.lease()!!).history
                check(uploaded.any { it.id == 222612L && it.completed && it.positionMs == observedEnd.first })
                check(uploaded.any { it.id == 222617L && !it.completed && it.positionMs in 1500..10000 })
                check(remote.writes == 2) { "unexpected playback upload count" }
            }
            result.putString("uploadRecovery", "PASS two real-playback records retained on failure, uploaded and confirmed by synthetic remote readback")
            if (phase == "service_refresh") {
                main { controller.play() }
                waitFor("second episode playing again") { main { controller.isPlaying } }
                val old = manager.lease()!!
                manager.logout(); login.uid=9502; manager.login("13800000000","123456")
                waitFor("new account verified during playback") { manager.state.value.uid==9502L && !manager.state.value.busy }
                runBlocking { sync.awaitIdle() }
                val originalRows = runBlocking { database.cloud().history(old.owner) }
                main { controller.pause() }
                runBlocking {
                    sync.awaitIdle()
                    check(database.cloud().history(old.owner)==originalRows) { "stale player changed old account after switch" }
                    check(database.cloud().history("account:9502").isEmpty() && database.cloud().pending("account:9502").isEmpty()) { "playback transferred to new account" }
                }
                check(manager.renewPlayback(old)==null)
                result.putString("switchIsolation", "PASS logout/new login during actual playback rejects old player pause for both accounts")
            }
            result.putInt("renderedAudioBuffers",rendered)
            result.putString("result","PASS actual service real audio natural tail transition: old completed/end outbox and distinct next breakpoint; owner isolated")
            finish(-1,result)
        } catch (failure: Throwable) { result.putString("failure",failure.javaClass.simpleName+": "+failure.message?.take(300)); finish(0,result) }
        finally { player?.let { main { it.pause(); it.release() } }; accountField.set(null,previousAccount); syncField.set(null,previousSync); scope.cancel(); database.close() }
    }

    private fun verifyAlbumProgress() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val file=File(targetContext.noBackupFilesDir,"album-cloud-contract.db");file.delete()
        val database=ListeningDatabase.open(targetContext,file.name)
        val manager=AccountManager(Login().apply {uid=9911},MemorySession(),targetContext.getSharedPreferences("album-cloud-contract",0),0,scope)
        waitFor("album manager ready") {!manager.state.value.busy}
        manager.login("13800000000","123456");waitFor("album fixture signed in") {manager.state.value.signedIn&&!manager.state.value.busy}
        val visible=mutableMapOf<Long,CloudHistoryRow>();val accepted=mutableMapOf<Long,CloudHistoryRow>()
        var writes=0;var fail=false;var delayVisibility=false
        val remote=object:CloudGateway {
            override fun read(lease:AccountLease)=CloudSnapshot(visible.values.toList(),emptyList())
            override fun subscription(lease:AccountLease,row:CloudSubscriptionRow) {error("not used")}
            override fun progress(lease:AccountLease,row:CloudHistoryRow) {
                if(fail) throw CloudFailure(message="同步收听进度：同步服务 HTTP 500",retryable=true)
                writes++;accepted[row.albumId]=row.copy(localAt=0,serverAt=row.localAt)
                if(!delayVisibility) visible[row.albumId]=accepted.getValue(row.albumId)
            }
        }
        val sync=CloudSync(manager,database.cloud(),remote,scope)
        try {
            waitFor("initial empty account sync complete") { sync.state.value.message == "服务器核对完成" && !sync.state.value.busy }
            runBlocking {
            sync.awaitIdle();val lease=manager.lease()!!;val dao=database.cloud()
            repeat(4) {i-> dao.record(CloudHistoryRow(lease.owner,800L+i,8,"episode$i","",10000,100000,false,1000L+i),i+1L)}
            // Server collapses episodes by album: the old implementation leaves three stuck.
            sync.sync();sync.awaitIdle()
            check(writes==1 && visible.getValue(8).id==803L) { "writes=$writes visible=${visible.values.map { it.id }}" }
            check(dao.pending(lease.owner).isEmpty());check(dao.history(lease.owner).size==4)
            check(syncViewState(sync.state.value)==SyncViewState.SYNCED)
            // Paused list/timeline events don't recreate already-confirmed tasks.
            sync.record(lease,HistoryRow(owner=lease.owner,id=803,albumId=8,page=1,title="episode3",cover="",positionMs=10999,durationMs=100000,completed=false,updatedAt=9000));sync.awaitIdle()
            check(dao.pending(lease.owner).isEmpty())
            // Upload success alone is insufficient: stale readback retains all pending rows.
            repeat(2) {i->dao.record(CloudHistoryRow(lease.owner,810L+i,8,"new$i","",20000,100000,false,2000L+i),10L+i)}
            delayVisibility=true;sync.sync();sync.awaitIdle();check(writes==2)
            check(dao.pending(lease.owner).size==2 && dao.pending(lease.owner).all {it.attempts==1})
            visible.putAll(accepted);sync.sync();sync.awaitIdle();check(writes==2 && dao.pending(lease.owner).isEmpty())
            // Rewind/latest episode wins by event time, not by highest episode ID/position.
            dao.record(CloudHistoryRow(lease.owner,800,8,"rewound","",1000,100000,false,3000),30)
            delayVisibility=false;sync.sync();sync.awaitIdle();check(visible.getValue(8).id==800L && visible.getValue(8).positionMs==1000L)
            // Distinct album and owner remain isolated, even with a newer remote timestamp.
            dao.record(CloudHistoryRow(lease.owner,900,9,"other album","",1000,100000,false,3001),31)
            dao.record(CloudHistoryRow("account:9912",800,8,"other owner","",1000,100000,false,1),1)
            fail=true;sync.sync();sync.awaitIdle();check(dao.pending(lease.owner).single().id==900L)
            check(sync.state.value.problem?.text=="进度上传失败（HTTP 500）")
            fail=false;sync.sync();sync.awaitIdle();check(dao.pending(lease.owner).isEmpty())
            check(dao.pending("account:9912").size==1)
        } } finally {sync.close();scope.cancel();database.close()}
    }

    private fun verifyHttpRetryRecovery() {
        for ((index, mode) in listOf("initial_read", "write_accepted", "final_confirmation").withIndex()) {
            val owner="account:${9701+index}"
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val source=Login().apply { uid=9701L+index }
            val manager=AccountManager(source,MemorySession(),targetContext.getSharedPreferences("http-retry-$index",Context.MODE_PRIVATE),0,scope)
            waitFor("retry manager idle") { !manager.state.value.busy }
            manager.login("13800000000","123456"); waitFor("retry login") { !manager.state.value.busy && manager.state.value.signedIn }
            val file=File(targetContext.noBackupFilesDir,"http-retry-$index.db"); file.delete()
            var database=ListeningDatabase.open(targetContext,file.name)
            var cloud: CloudSync?=null
            val now=java.util.concurrent.atomic.AtomicLong(1_000_000)
            val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
            val writes=java.util.concurrent.atomic.AtomicInteger()
            var accepted=false; var firstFailure=true; var failReopenRead=false
            val client=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val req=chain.request(); val path=req.url.encodedPath
                requests.add(req.method+" "+path)
                val isWrite=req.method=="POST"
                var status=200
                if (failReopenRead && !isWrite) { status=503; failReopenRead=false }
                else if (firstFailure && mode=="initial_read") { status=503; firstFailure=false }
                else if (isWrite) {
                    writes.incrementAndGet(); accepted=true
                    if (firstFailure && mode=="write_accepted") { status=503; firstFailure=false }
                } else if (firstFailure && mode=="final_confirmation" && accepted) { status=503; firstFailure=false }
                val body=if(path.contains("history/query")) "{\"ret\":0,\"data\":{\"listenModels\":[]}}"
                    else if(path.contains("subscribe/list")) JSONObject().put("ret",0).put("data",JSONObject().put("subscribeAlbumResult",JSONObject().put("list",org.json.JSONArray().apply { if(accepted) put(JSONObject().put("albumId",99701).put("title","retry")) }))).toString()
                    else "{\"ret\":0}"
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("controlled fixture").body(body.toResponseBody()).build()
            }.build()
            val api=CloudApi(targetContext,client,current=manager::current,sign={ "test-only-unused" })
            fun openSync(): CloudSync = CloudSync(manager,database.cloud(),api,CoroutineScope(SupervisorJob()+Dispatchers.IO),now::get).also { cloud=it }
            fun reopen() { cloud!!.close(); database.close(); database=ListeningDatabase.open(targetContext,file.name) }
            fun pending(): SyncRow = runBlocking { database.cloud().pending(owner).single() }
            try {
                runBlocking { database.cloud().subscribe(CloudSubscriptionRow(owner,99701,"retry","",true),42) }
                var sync=openSync()
                waitFor("$mode initial 503 deferred") { !sync.state.value.busy && sync.state.value.pending==1 && runBlocking { database.cloud().pending(owner).single().attempts==1 } }
                val first=pending()
                check(first.nextAt in (now.get()+1)..(now.get()+1_800_000)) { "$mode nonfinite backoff" }
                check(first.revision==42L)
                val writesBefore=writes.get()
                // Reopen Room and worker before the saved deadline: automatic observer may query,
                // but it must not resend. For accepted writes, keep that first reopen uncertain.
                reopen(); if(mode!="initial_read") failReopenRead=true
                sync=openSync()
                waitFor("$mode before-deadline automatic query") { sync.state.value.owner==owner && !sync.state.value.busy && sync.state.value.pending==1 }
                runBlocking { sync.awaitIdle() }
                check(writes.get()==writesBefore) { "$mode wrote before persisted deadline" }
                val saved=pending(); check(saved.revision==42L && saved.nextAt<Long.MAX_VALUE && saved.nextAt>now.get())
                reopen()
                runBlocking { check(database.cloud().pending(owner).single()==saved) }
                now.set(saved.nextAt+1)
                val boundary=requests.size
                sync=openSync() // automatic startup, never manual sync (which bypasses deadlines)
                waitFor("$mode automatic deadline recovery") { sync.state.value.owner==owner && !sync.state.value.busy && sync.state.value.pending==0 && sync.state.value.message=="服务器核对完成" }
                val recovered=requests.drop(boundary)
                check(recovered.first().contains("history/query") && recovered[1].contains("subscribe/list")) { "recovery did not query before resend" }
                check(writes.get()==1) { "$mode accepted write duplicated: ${writes.get()}" }
                runBlocking { check(database.cloud().pending(owner).isEmpty()); check(database.cloud().subscriptions(owner).single().let { it.subscribed && it.confirmed }) }
                sendStatus(0,Bundle().apply { putString("passed","http503_$mode finite saved deadline, Room reopen before/after deadline, automatic query-before-resend, one accepted write") })
            } finally { cloud?.close(); database.close(); scope.cancel() }
        }
    }

    override fun onStart() {
        if (phase == "ui") { runUiVerification(); return }
        if (phase == "service_natural" || phase == "service_refresh") { runNaturalServiceVerification(); return }
        if (phase == "restart_seed" || phase == "restart_restore") {
            val result = Bundle()
            val name = "sync-restart-verification.db"
            try {
                if (phase == "restart_seed") File(targetContext.noBackupFilesDir, name).delete()
                val database = ListeningDatabase.open(targetContext, name)
                try { runBlocking {
                    val dao = database.cloud()
                    if (phase == "restart_seed") {
                        dao.record(CloudHistoryRow("account:701",222612,1,"restart fixture","",42000,100000,false,5000),42)
                        dao.subscribe(CloudSubscriptionRow("account:701",1001,"restart album","",true),43)
                        dao.defer("account:701","history",222612,42,3,9000000000000)
                    } else {
                        check(dao.history("account:701").single().positionMs == 42000L)
                        check(dao.subscriptions("account:701").single().subscribed)
                        check(dao.pending("account:701").first { it.kind == "history" }.let { it.revision == 42L && it.attempts == 3 && it.nextAt == 9000000000000L })
                        check(dao.history("account:702").isEmpty() && dao.pending("account:702").isEmpty())
                    }
                } } finally { database.close() }
                result.putString("result", "PASS " + phase); finish(-1,result)
            } catch (error: Throwable) { result.putString("failure", error.javaClass.simpleName + ": " + error.message); finish(0,result) }
            return
        }
        val result = Bundle(); val completed = arrayListOf<String>()
        val file = File(targetContext.noBackupFilesDir, "sync-verification.db")
        val scopes = mutableListOf<CoroutineScope>(); var db: ListeningDatabase? = null
        fun test(name: String, action: () -> Unit) { action(); completed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        try {
            test("album_latest_progress_coalesces_and_confirms_without_losing_local_history") { verifyAlbumProgress() }
            test("sync_failure_stage_reason_and_no_secret_disclosure") {
                check(SyncProblem.from("同步结果确认", CloudFailure(message="读取账号订阅：同步服务业务拒绝（924）secret-token")).text=="订阅读取未通过（924）")
                check(SyncProblem.from("进度上传", CloudFailure(message="同步收听进度：同步服务 HTTP 503，secret-token",retryable=true)).text=="进度上传失败（HTTP 503）")
                check(SyncProblem.from("账号记录读取", CloudReadFailure("历史读取",org.json.JSONException("secret-token"))).text=="历史读取失败（数据格式异常）")
                check(SyncProblem.from("账号记录读取", CloudReadFailure("订阅读取",java.net.SocketTimeoutException("secret-token"))).text=="订阅读取超时，请稍后重试")
                check(!SyncProblem.from("进度上传",java.io.IOException("secret-token")).text.contains("secret"))
            }
            test("real_v1_to_v2_migration_preserves_guest") {
                file.delete()
                val schema = JSONObject(context.assets.open("stage4-schema.json").bufferedReader().readText()).getJSONObject("database")
                SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) { val e = entities.getJSONObject(i); sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))) }
                    sql.execSQL("INSERT INTO history VALUES ('guest',222612,1,1,'guest fixture','',42000,100000,0,1000)")
                    sql.execSQL("INSERT INTO queue VALUES ('guest',0,222612,1,1,'guest fixture','')")
                    sql.execSQL("INSERT INTO cursor VALUES ('guest',0,42000)")
                    sql.version = 1
                }
                db = ListeningDatabase.open(targetContext, file.name)
                runBlocking { check(db!!.records().history().single().positionMs == 42000L); check(db!!.records().cursor()!!.positionMs == 42000L); check(db!!.cloud().pending("account:101").isEmpty()) }
            }
            val dao = db!!.cloud(); val login = Login(); val session = MemorySession()
            val prefs = targetContext.getSharedPreferences("sync-fixture-only", Context.MODE_PRIVATE)
            val mergeFile = File(targetContext.noBackupFilesDir,"suite-first-account-history").apply { delete() }
            fun newAccount(): AccountManager {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); scopes.add(scope)
                return AccountManager(login, session, prefs, 0, scope,GuestHistoryBinding(mergeFile)::claim).also { manager -> waitFor("initial account idle") { !manager.state.value.busy } }
            }
            val account = newAccount(); val remote = Remote(); val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) }
            val sync = CloudSync(account, dao, remote, syncScope, guestMerge=GuestHistoryMerge(mergeFile,db!!.records()))
            fun signIn(uid: Long) { login.uid = uid; account.login("13800000000", "123456"); waitFor("verified login") { !account.state.value.busy && account.state.value.uid == uid }; waitFor("account view") { sync.state.value.owner == "account:$uid" }; runBlocking { sync.awaitIdle() } }
            signIn(101)
            test("first_verified_login_automatically_merges_guest_and_preserves_original") { check(remote.writes == 1); runBlocking { check(dao.history("account:101").single().id==222612L); check(db!!.records().history().single().owner == "guest") }; val writes=remote.writes; sync.sync(); runBlocking { sync.awaitIdle() }; check(remote.writes==writes) }
            test("subscription_confirm_and_reverse") {
                sync.toggle(Item(1001, "album fixture")); runBlocking { sync.awaitIdle() }
                check(sync.state.value.subscriptions.single().let { it.subscribed && it.confirmed }); check(sync.state.value.pending == 0)
                sync.toggle(Item(1001, "album fixture")); runBlocking { sync.awaitIdle() }
                check(!sync.state.value.subscriptions.single().subscribed); check(sync.state.value.pending == 0)
            }
            test("timeout_after_server_acceptance_reconciles_without_duplicate") {
                remote.failAfterSubscription = true; sync.toggle(Item(1002, "timeout album")); runBlocking { sync.awaitIdle() }
                check(sync.state.value.pending == 1); check(!sync.state.value.busy); val writes = remote.writes
                sync.sync(); runBlocking { sync.awaitIdle() }
                check(remote.writes == writes); check(sync.state.value.pending == 0)
                check(sync.state.value.subscriptions.first { it.id == 1002L }.confirmed)
            }
            test("offline_history_persistent_outbox_and_manual_recovery") {
                remote.fail = true
                val lease = account.lease()!!
                sync.record(lease, HistoryRow(id=222613, albumId=1, page=1, title="local listen", cover="", positionMs=55000, durationMs=100000, completed=false, updatedAt=5000))
                runBlocking { sync.awaitIdle() }; sync.sync(); runBlocking { sync.awaitIdle() }
                check(sync.state.value.pending == 1); check(!sync.state.value.busy)
                runBlocking { check(dao.pending(lease.owner).single().attempts > 0); check(dao.history(lease.owner).first { it.id==222613L }.positionMs == 55000L) }
                val before = runBlocking { dao.pending(lease.owner).single() }
                sync.record(lease, HistoryRow(id=222613, albumId=1, page=1, title="local listen", cover="", positionMs=56000, durationMs=100000, completed=false, updatedAt=6000))
                runBlocking { sync.awaitIdle(); val after = dao.pending(lease.owner).single(); check(after.attempts == before.attempts && after.nextAt == before.nextAt) }
                remote.fail = false; sync.sync(); runBlocking { sync.awaitIdle() }
                check(sync.state.value.pending == 0); check(sync.state.value.history.first { it.id==222613L }.serverAt == 6000L)
            }
            test("timestamp_conflict_server_newer_and_tie_win") {
                runBlocking { dao.record(CloudHistoryRow("account:101", 222614, 1, "older", "", 90000, 100000, false, 1000), 1000) }
                remote.histories.getOrPut("account:101") { mutableListOf() }.add(CloudHistoryRow("account:101",222614,1,"server newer","",10000,100000,false,0,2000))
                sync.sync(); runBlocking { sync.awaitIdle() }
                check(sync.state.value.history.first { it.id == 222614L }.positionMs == 10000L)
            }
            test("cached_server_history_older_rejected_and_ties_prefer_server") {
                runBlocking {
                    val owner="account:timestamp-fixture"
                    val newest=CloudHistoryRow(owner,99001,1,"newest","",30000,30000,true,0,2000)
                    dao.reconcile(owner,CloudSnapshot(listOf(newest),emptyList()))
                    dao.reconcile(owner,CloudSnapshot(listOf(newest.copy(positionMs=1000,completed=false,serverAt=1000)),emptyList()))
                    check(dao.history(owner).single()==newest) { "older confirmed server state regressed" }
                    val tie=newest.copy(title="server tie",positionMs=12000,completed=false)
                    dao.reconcile(owner,CloudSnapshot(listOf(tie),emptyList()))
                    check(dao.history(owner).single()==tie) { "server tie did not replace confirmed cache" }
                    dao.record(tie.copy(positionMs=25000,localAt=3000),3000)
                    val localTie=tie.copy(positionMs=8000,serverAt=3000)
                    dao.reconcile(owner,CloudSnapshot(listOf(localTie),emptyList()))
                    check(dao.history(owner).single()==localTie && dao.pending(owner).isEmpty())
                }
            }
            test("refresh_lineage_multiple_refreshes_login_logout_isolation") {
                val old=account.lease()!!
                repeat(2) { account.refresh(); waitFor("refresh settles") { !account.state.value.busy } }
                check(!account.current(old)); check(account.renewPlayback(old)?.session === account.lease()!!.session)
                val uid=account.state.value.uid
                account.login("13800000000","123456"); waitFor("same uid explicit relogin") { !account.state.value.busy && account.state.value.signedIn }
                check(account.state.value.uid==uid && account.renewPlayback(old)==null) { "explicit login renewed old playback" }
                val beforeLogout=account.lease()!!
                account.logout(); signIn(uid)
                check(account.renewPlayback(beforeLogout)==null) { "logout and same uid login renewed old playback" }
            }
            test("automatic_first_account_batch_not_copied_to_subsequent_account") {
                sync.sync(); runBlocking { sync.awaitIdle() }
                check(remote.histories["account:101"]!!.any { it.id == 222612L })
                account.logout(); signIn(202)
                check(sync.state.value.history.isEmpty()); check(sync.state.value.subscriptions.isEmpty())
                runBlocking { check(dao.history("account:101").isNotEmpty()); check(dao.history("account:202").isEmpty()) }
            }
            test("stale_account_response_and_record_rejected_then_new_sync_usable") {
                val old = account.lease()!!; remote.entered = CountDownLatch(1); remote.release = CountDownLatch(1)
                sync.sync(); check(remote.entered!!.await(5, TimeUnit.SECONDS)); account.logout(); remote.release!!.countDown()
                waitFor("logout hides old account") { sync.state.value.owner.isEmpty() }
                remote.entered = null; remote.release = null; signIn(303)
                sync.record(old, HistoryRow(id=9, albumId=1,page=1,title="old",cover="",positionMs=9,durationMs=10,completed=false,updatedAt=100))
                runBlocking { sync.awaitIdle(); check(dao.history("account:303").isEmpty()) }
                sync.toggle(Item(1003,"new account")); runBlocking { sync.awaitIdle() }; check(sync.state.value.pending == 0)
            }
            test("expired_session_hides_cloud_and_retains_owner_outbox") {
                remote.fail = true; sync.toggle(Item(1004,"pending")); runBlocking { sync.awaitIdle() }; check(sync.state.value.pending == 1)
                remote.fail = false; remote.rejected = true; sync.sync(); runBlocking { sync.awaitIdle() }
                waitFor("expired hidden") { !account.state.value.signedIn && sync.state.value.owner.isEmpty() }
                runBlocking { check(dao.pending("account:303").single().id == 1004L) }
                remote.rejected = false; signIn(404); check(sync.state.value.pending == 0)
            }
            test("unverified_refresh_keeps_confirmed_session") {
                val old = account.lease()!!.session; login.valid = false; account.refresh()
                waitFor("refresh failure releases busy") { !account.state.value.busy }
                check(account.lease()!!.session === old && session.value === old)
                login.valid = true
            }
            test("old_refresh_cannot_resurrect_logout") {
                login.entered = CountDownLatch(1); login.release = CountDownLatch(1)
                account.refresh(); check(login.entered!!.await(5, TimeUnit.SECONDS)); account.logout(); login.release!!.countDown()
                Thread.sleep(100); check(!account.state.value.signedIn && account.lease() == null && session.value == null)
                login.entered = null; login.release = null; signIn(505)
            }
            test("revision_specific_ack_and_reopen_outbox") {
                runBlocking { dao.subscribe(CloudSubscriptionRow("account:505",123,"reversed","",true),10); dao.subscribe(CloudSubscriptionRow("account:505",123,"reversed","",false),11); check(dao.acknowledge("account:505","subscription",123,10)==0) }
                sync.close(); db!!.close(); db = ListeningDatabase.open(targetContext,file.name)
                runBlocking { check(db!!.cloud().pending("account:505").single().revision==11L); check(!db!!.cloud().subscriptions("account:505").single().subscribed); check(db!!.records().history().single().positionMs==42000L) }
            }
            test("http503_persisted_bounded_backoff_and_automatic_reopen_recovery") { verifyHttpRetryRecovery() }
            test("http_failure_retryability_does_not_retry_business_rejection") {
                val lease=account.lease()!!
                for (status in listOf(500,502,503,401,403,400,200)) {
                    val client=OkHttpClient.Builder().addInterceptor { chain ->
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                            .body("{\"ret\":123}".toResponseBody()).build()
                    }.build()
                    try { CloudApi(targetContext,client,current=account::current).read(lease); error("expected rejection") }
                    catch(failure:CloudFailure) { check(failure.retryable==(status in 500..599)); check(failure.expired==(status==401 || status==403)) }
                }
            }
            test("production_read_failure_keeps_stage_and_cancellation") {
                val lease=account.lease()!!
                for (subscriptions in listOf(false,true)) {
                    val client=OkHttpClient.Builder().addInterceptor { chain ->
                        val body=if(subscriptions && chain.request().url.encodedPath.contains("history/query")) """{"ret":0,"data":{"listenModels":[]}}""" else """{"ret":0,"data":{}}"""
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
                    }.build()
                    try { CloudApi(targetContext,client,current=account::current).read(lease);error("expected malformed response") }
                    catch(error:CloudReadFailure) { check(error.stage==if(subscriptions) "订阅读取" else "历史读取");check(error.cause is org.json.JSONException) }
                }
                val cancel=java.util.concurrent.CancellationException("fixture")
                val client=OkHttpClient.Builder().addInterceptor { throw cancel }.build()
                try { CloudApi(targetContext,client,current=account::current).read(lease);error("expected cancellation") }
                catch(error:java.util.concurrent.CancellationException) { check(error===cancel) }
            }
            test("production_http_contract_no_credential_redirect_or_media_client") {
                val requests = mutableListOf<Request>()
                val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
                    val req = chain.request(); requests.add(req)
                    check(req.url.isHttps && req.url.host in setOf("mobile.ximalaya.com","xdcs-collector.ximalaya.com"))
                    val json = if (req.url.encodedPath.contains("history/query")) "{\"ret\":0,\"data\":{\"listenModels\":[{\"type\":1,\"itemId\":1,\"childId\":2,\"endedAt\":5000,\"breakSecond\":12,\"length\":30,\"childTitle\":\"contract\"}]}}" else if(req.url.encodedPath.contains("subscribe/list")) "{\"ret\":0,\"data\":{\"subscribeAlbumResult\":{\"list\":[{\"albumId\":3,\"title\":\"album\"}]}}}" else "{\"ret\":0,\"data\":{\"syncPoint\":10}}"
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(json.toResponseBody()).build()
                }.build()
                val api = CloudApi(targetContext,client,current=account::current); val lease = account.lease()!!
                val data = api.read(lease); check(data.history.single().positionMs==12000L); check(data.subscriptions.single().id==3L)
                api.subscription(lease,CloudSubscriptionRow(lease.owner,3,"album","",true))
                val body = Buffer(); requests.last().body!!.writeTo(body); check(body.readUtf8().contains("albumId=3")); check(requests.last().body!!.contentType().toString().contains("x-www-form-urlencoded"))
                api.progress(lease, CloudHistoryRow(lease.owner,2,1,"contract","",12000,30000,false,5000))
                val signedBody = Buffer(); requests.last().body!!.writeTo(signedBody); val form = signedBody.readUtf8()
                check(form.contains("mergeRecords=") && form.contains("signature=") && !form.contains("synthetic-505"))
                check(requests.last().url.host == "xdcs-collector.ximalaya.com")
                check(requests.all { it.header("Cookie")!!.contains("synthetic-505") }); check(!client.followRedirects && !client.followSslRedirects)
            }
            test("logout_during_signing_sends_no_history_http_and_new_account_usable") {
                val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) }
                val source = Login().apply { uid=8801 }
                val manager = AccountManager(source,MemorySession(),targetContext.getSharedPreferences("lease-send-fixture",Context.MODE_PRIVATE),0,localScope)
                waitFor("send fixture initialized") { !manager.state.value.busy }
                manager.login("13800000000","123456"); waitFor("send fixture logged in") { manager.state.value.uid==8801L && !manager.state.value.busy }
                val old = manager.lease()!!
                runBlocking { db!!.cloud().record(CloudHistoryRow(old.owner,88001,1,"old pending","",12000,30000,false,5000),88001) }
                val writes = java.util.concurrent.atomic.AtomicInteger(); val requests = java.util.concurrent.atomic.AtomicInteger()
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val req=chain.request(); requests.incrementAndGet()
                    if(req.url.encodedPath.contains("merge")) writes.incrementAndGet()
                    val body=if(req.url.encodedPath.contains("history/query")) "{\"ret\":0,\"data\":{\"listenModels\":[]}}" else "{\"ret\":0,\"data\":{\"subscribeAlbumResult\":{\"list\":[]}}}"
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
                }.build()
                val api=CloudApi(targetContext,client,current=manager::current,sign={ entered.countDown(); check(release.await(10,TimeUnit.SECONDS)); "fixture-signature" })
                val cloud=CloudSync(manager,db!!.cloud(),api,localScope)
                try {
                    check(entered.await(5,TimeUnit.SECONDS)) { "signer did not block" }
                    manager.logout(); source.uid=8802; manager.login("13800000000","123456")
                    waitFor("new verified account") { manager.state.value.uid==8802L && !manager.state.value.busy }
                    val before=requests.get(); release.countDown(); runBlocking { cloud.awaitIdle() }
                    waitFor("new account view") { cloud.state.value.owner=="account:8802" }
                    cloud.sync(); runBlocking { cloud.awaitIdle() }
                    check(writes.get()==0) { "old history sent after logout" }; check(requests.get()>before)
                    runBlocking { check(db!!.cloud().pending(old.owner).single().revision==88001L); check(db!!.cloud().pending("account:8802").isEmpty()) }
                    check(!cloud.state.value.busy)
                } finally { release.countDown(); cloud.close() }
            }
            test("lease_invalidation_between_pages_prevents_next_http") {
                val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) }
                val manager=AccountManager(Login().apply { uid=8901 },MemorySession(),targetContext.getSharedPreferences("lease-page-fixture",Context.MODE_PRIVATE),0,localScope)
                waitFor("page fixture initialized") { !manager.state.value.busy }
                manager.login("13800000000","123456"); waitFor("page fixture login") { manager.state.value.uid==8901L && !manager.state.value.busy }
                val lease=manager.lease()!!; val count=java.util.concurrent.atomic.AtomicInteger()
                val full=org.json.JSONArray().apply { repeat(20) { put(JSONObject().put("type",0)) } }
                val client=OkHttpClient.Builder().addInterceptor { chain ->
                    val req=chain.request(); check(count.incrementAndGet()==1) { "stale second page sent" }
                    manager.logout()
                    val body=JSONObject().put("ret",0).put("data",JSONObject().put("listenModels",full)).toString()
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
                }.build()
                try { CloudApi(targetContext,client,current=manager::current).read(lease); error("invalidated pagination completed") }
                catch(failure: CloudFailure) { check(failure.message!!.contains("会话已改变")) }
                check(count.get()==1); check(manager.lease()==null)
            }
            test("native_history_signer_process_isolation_and_delayed_retry") {
                val mainPid = android.os.Process.myPid()
                val fields = mapOf("mergeRecords" to "[]", "uid" to "505", "token" to "synthetic-505", "device" to "android", "deviceId" to "fixture", "version" to "9.4.74.3", "channel" to "default", "impl" to targetContext.packageName)
                val value = HistorySigner.sign(targetContext,fields)
                check(value.isNotBlank() && !value.contains("synthetic-505"))
                Thread.sleep(15_000)
                check(android.os.Process.myPid() == mainPid)
                // A late native exit is confined to the secondary process. A new bind either
                // returns a signature or an explicit recoverable failure, never killing this UI.
                try { check(HistorySigner.sign(targetContext,fields).isNotBlank()) }
                catch (failure: CloudFailure) { check(failure.message!!.contains("保留")) }
                check(android.os.Process.myPid() == mainPid)
                val connected = CountDownLatch(1); val died = CountDownLatch(1)
                val connection = object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                        binder.linkToDeath({ died.countDown() }, 0); connected.countDown()
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName) { died.countDown() }
                }
                check(targetContext.bindService(Intent(targetContext, HistorySignerService::class.java), connection, Context.BIND_AUTO_CREATE))
                try {
                    check(connected.await(10, TimeUnit.SECONDS))
                    val processes = (targetContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses
                    val signer = processes.single { it.processName == targetContext.packageName + ":historySigner" }
                    check(signer.pid != mainPid); android.os.Process.killProcess(signer.pid)
                    check(died.await(10, TimeUnit.SECONDS)) { "private signer Binder death not observed" }
                } finally { targetContext.unbindService(connection) }
                check(android.os.Process.myPid() == mainPid)
                check(HistorySigner.sign(targetContext,fields).isNotBlank())
                val owner = "account:505"
                runBlocking { check(db!!.cloud().pending(owner).single().revision == 11L) }
            }
            result.putString("result","PASS"); result.putStringArrayList("tests",completed); finish(-1,result)
        } catch (failure: Throwable) {
            result.putString("result","FAIL"); result.putString("failure",failure.stackTraceToString()); result.putStringArrayList("tests",completed); finish(0,result)
        } finally { scopes.forEach { it.cancel() }; db?.close() }
    }
}
