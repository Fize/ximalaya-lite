package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Real Room/AtomicFile/AccountManager, synthetic gateway only; no SMS or real cloud writes. */
class AutoHistoryVerification : Instrumentation() {
    private var phase = "suite"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase=arguments?.getString("phase") ?: "suite"; start() }
    private class Sessions : SessionStore {
        var session: AccountSession?=null
        override fun read()=session
        override fun write(session: AccountSession) { this.session=session }
        override fun clear() { session=null }
    }
    private class Login : AccountGateway {
        var uid=6101L
        var failCheck=false
        override fun send(mobile:String) { error("SMS prohibited") }
        override fun login(mobile:String,code:String)=AccountSession(uid,"synthetic-$uid")
        override fun check(session:AccountSession):Boolean { if(failCheck) throw AccountFailure("check","fixture offline"); return true }
    }
    private class Remote(private val binding:File) : CloudGateway {
        var offline=false
        val rows=mutableMapOf<String,MutableList<CloudHistoryRow>>()
        val writes=mutableListOf<Pair<String,Long>>()
        override fun read(lease:AccountLease):CloudSnapshot {
            check(JSONObject(binding.readText()).getString("owner").startsWith("account:"))
            if(offline) throw java.io.IOException("fixture offline")
            return CloudSnapshot(rows[lease.owner].orEmpty().toList(),emptyList())
        }
        override fun subscription(lease:AccountLease,row:CloudSubscriptionRow) { error("not used") }
        override fun progress(lease:AccountLease,row:CloudHistoryRow) {
            val state=JSONObject(binding.readText()); check(state.getString("owner")==lease.owner && state.getBoolean("complete"))
            writes.add(lease.owner to row.id)
            rows.getOrPut(lease.owner) { mutableListOf() }.apply { removeAll { it.id==row.id }; add(row.copy(localAt=0,serverAt=row.localAt)) }
        }
    }
    private fun waitFor(label:String,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!condition() && System.nanoTime()<end) Thread.sleep(10)
        check(condition()) { label }
    }
    override fun onStart() {
        val result=Bundle(); val passed=arrayListOf<String>(); val scopes=mutableListOf<CoroutineScope>(); val databases=mutableListOf<ListeningDatabase>()
        fun scope()=CoroutineScope(SupervisorJob()+Dispatchers.IO).also { scopes.add(it) }
        fun database(name:String,reset:Boolean=true):ListeningDatabase {
            if(reset) File(targetContext.noBackupFilesDir,name).delete()
            return ListeningDatabase.open(targetContext,name).also { databases.add(it) }
        }
        fun manager(login:Login,path:File,store:Sessions=Sessions()):AccountManager = AccountManager(login,store,targetContext.getSharedPreferences("auto-history-fixture",Context.MODE_PRIVATE),0,scope(),GuestHistoryBinding(path)::claim).also { account -> waitFor("initial account") { !account.state.value.busy } }
        fun signIn(account:AccountManager,login:Login,uid:Long) {
            login.uid=uid; account.login("13800000000","123456"); waitFor("verified $uid") { account.state.value.uid==uid && !account.state.value.busy && account.state.value.signedIn }
        }
        fun row(id:Long,at:Long=100,owner:String=LOCAL_OWNER)=HistoryRow(owner,id,1,1,"fixture$id","",id*1000,100000,false,at)
        fun test(name:String,block:()->Unit) { block(); passed.add(name); sendStatus(0,Bundle().apply { putString("passed",name) }) }
        try {
            if(phase=="restart_seed" || phase=="restart_restore") {
                val db=database("auto-history-restart.db",phase=="restart_seed")
                val path=File(targetContext.noBackupFilesDir,"auto-history-restart-binding")
                if(phase=="restart_seed") path.delete()
                val login=Login(); val account=manager(login,path); signIn(account,login,6101)
                if(phase=="restart_seed") { runBlocking { db.records().putHistory(row(1)); db.records().putHistory(row(2)) }
                    var checks=0
                    runBlocking { GuestHistoryMerge(path,db.records()).stage(account.lease()!!,db.cloud(),{ lease ->
                        if(++checks==3) account.logout()
                        account.current(lease)
                    },{ 123 }) }
                    check(JSONObject(path.readText()).let { it.getString("owner")=="account:6101" && !it.getBoolean("complete") })
                    runBlocking { check(db.cloud().pending("account:6101").single().revision==123L) }
                } else {
                    check(!JSONObject(path.readText()).getBoolean("complete"))
                    runBlocking {
                        val first=db.cloud().pending("account:6101").single()
                        GuestHistoryMerge(path,db.records()).stage(account.lease()!!,db.cloud(),account::current,{456})
                        check(db.cloud().pending("account:6101").size==2)
                        check(db.cloud().pending("account:6101").first { it.id==first.id }.revision==123L)
                        check(db.cloud().history("account:6102").isEmpty())
                    }
                    check(JSONObject(path.readText()).getBoolean("complete"))
                }
                result.putString("result","PASS $phase actual AtomicFile/Room interrupted batch recovery"); finish(-1,result); return
            }
            test("first_verified_login_guest_only_conflicts_originals_and_repeat") {
                val db=database("auto-history-first.db"); val path=File(targetContext.noBackupFilesDir,"auto-history-first-binding").apply { delete() }
                runBlocking {
                    db.records().putHistory(row(1,100)); db.records().putHistory(row(2,600)); db.records().putHistory(row(3,100)); db.records().putHistory(row(4,999,"account:9999"))
                    db.cloud().putHistory(CloudHistoryRow("account:6101",3,1,"newer local","",9000,100000,false,900))
                }
                val remote=Remote(path).apply { rows["account:6101"]=mutableListOf(CloudHistoryRow("account:6101",1,1,"newer remote","",77000,100000,false,0,500)) }
                val login=Login(); val account=manager(login,path)
                var cloud=CloudSync(account,db.cloud(),remote,scope(),guestMerge=GuestHistoryMerge(path,db.records()))
                check(!path.exists()); signIn(account,login,6101)
                waitFor("first batch automatic") { cloud.state.value.owner=="account:6101" }; runBlocking { cloud.awaitIdle() }
                runBlocking {
                    val rows=db.cloud().history("account:6101"); check(rows.map { it.id }.toSet()==setOf(1L,2L,3L))
                    check(rows.first { it.id==1L }.positionMs==77000L); check(rows.first { it.id==3L }.localAt==900L)
                    check(db.records().history().size==3 && db.records().history("account:9999").single().id==4L)
                }
                check(remote.writes==listOf("account:6101" to 2L))
                val writes=remote.writes.toList(); cloud.sync(); runBlocking { cloud.awaitIdle() }; check(remote.writes==writes)
                cloud.close(); runBlocking { db.records().putHistory(row(5,1000)) }
                cloud=CloudSync(account,db.cloud(),remote,scope(),guestMerge=GuestHistoryMerge(path,db.records()))
                waitFor("same verified account reopened") { cloud.state.value.owner=="account:6101" }; runBlocking { cloud.awaitIdle() }
                check(remote.writes==writes); runBlocking { check(db.cloud().history("account:6101").none { it.id==5L }) }
                account.logout(); signIn(account,login,6102); waitFor("other account") { cloud.state.value.owner=="account:6102" }; runBlocking { cloud.awaitIdle(); check(db.cloud().history("account:6102").isEmpty()) }
                check(JSONObject(path.readText()).getString("owner")=="account:6101"); cloud.close()
            }
            test("offline_first_batch_recovers_only_original_account") {
                val db=database("auto-history-offline.db"); val path=File(targetContext.noBackupFilesDir,"auto-history-offline-binding").apply { delete() }
                runBlocking { db.records().putHistory(row(8)) }
                val login=Login(); val account=manager(login,path); val remote=Remote(path).apply { offline=true }
                val cloud=CloudSync(account,db.cloud(),remote,scope(),guestMerge=GuestHistoryMerge(path,db.records()))
                signIn(account,login,6101); waitFor("offline batch") { cloud.state.value.owner=="account:6101" }; runBlocking { cloud.awaitIdle(); check(db.cloud().pending("account:6101").single().id==8L) }
                check(JSONObject(path.readText()).getBoolean("complete")); check(remote.writes.isEmpty())
                account.logout(); signIn(account,login,6102); waitFor("offline other owner") { cloud.state.value.owner=="account:6102" }; runBlocking { cloud.awaitIdle(); check(db.cloud().pending("account:6102").isEmpty()) }
                remote.offline=false; account.logout(); signIn(account,login,6101); waitFor("original restored") { cloud.state.value.owner=="account:6101" }; cloud.sync(); runBlocking { cloud.awaitIdle(); check(db.cloud().pending("account:6101").isEmpty()) }
                check(remote.writes==listOf("account:6101" to 8L)); cloud.close()
            }
            test("account_switch_during_partial_import_claims_old_owner_and_resumes_idempotently") {
                val db=database("auto-history-switch.db"); val path=File(targetContext.noBackupFilesDir,"auto-history-switch-binding").apply { delete() }
                runBlocking { db.records().putHistory(row(11)); db.records().putHistory(row(12)) }
                val login=Login(); val account=manager(login,path); signIn(account,login,6101); val old=account.lease()!!
                var checks=0
                runBlocking { GuestHistoryMerge(path,db.records()).stage(old,db.cloud(),{ lease -> if(++checks==3) account.logout(); account.current(lease) },{101}) }
                check(!JSONObject(path.readText()).getBoolean("complete")); runBlocking { check(db.cloud().pending(old.owner).size==1) }
                signIn(account,login,6102)
                runBlocking { GuestHistoryMerge(path,db.records()).stage(account.lease()!!,db.cloud(),account::current,{202}); check(db.cloud().pending("account:6102").isEmpty()) }
                account.logout(); signIn(account,login,6101)
                runBlocking { GuestHistoryMerge(path,db.records()).stage(account.lease()!!,db.cloud(),account::current,{303}); check(db.cloud().pending(old.owner).size==2); check(db.cloud().pending(old.owner).any { it.revision==101L }) }
                check(JSONObject(path.readText()).getBoolean("complete"))
            }
            test("switch_before_collector_or_before_stage_keeps_first_verified_owner") {
                for (beforeCollector in listOf(true,false)) {
                    val db=database("auto-history-early-$beforeCollector.db")
                    val path=File(targetContext.noBackupFilesDir,"auto-history-early-$beforeCollector-binding").apply { delete() }
                    runBlocking { db.records().putHistory(row(31)) }
                    val login=Login(); val account=manager(login,path); val remote=Remote(path)
                    val queued=java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
                    val dispatcher=object:CoroutineDispatcher() {
                        override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) { queued.add(block) }
                    }
                    val syncScope=CoroutineScope(SupervisorJob()+dispatcher).also { scopes.add(it) }
                    val cloud=CloudSync(account,db.cloud(),remote,syncScope,guestMerge=GuestHistoryMerge(path,db.records()))
                    // Start worker/collector only for the pre-stage case; then leave queued work paused.
                    if(!beforeCollector) repeat(2) { checkNotNull(queued.poll()).run() }
                    signIn(account,login,6101)
                    check(JSONObject(path.readText()).getString("owner")=="account:6101")
                    if(!beforeCollector) checkNotNull(queued.poll()).run() // collector queues A's sync
                    account.logout(); signIn(account,login,6102)
                    fun drainUntil(label:String,condition:()->Boolean) {
                        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                        while(!condition() && System.nanoTime()<end) { queued.poll()?.run() ?: Thread.sleep(5) }
                        check(condition()) { label }
                    }
                    drainUntil("B synchronized") { cloud.state.value.owner=="account:6102" && cloud.state.value.message=="服务器核对完成" }
                    runBlocking { check(db.cloud().history("account:6102").isEmpty()); check(db.cloud().pending("account:6102").isEmpty()) }
                    check(remote.writes.isEmpty()); check(JSONObject(path.readText()).getString("owner")=="account:6101")
                    account.logout(); signIn(account,login,6101)
                    drainUntil("A resumes import") { remote.writes==listOf("account:6101" to 31L) && cloud.state.value.message=="服务器核对完成" }
                    check(JSONObject(path.readText()).getBoolean("complete")); cloud.close()
                }
            }
            test("restored_pending_session_claims_on_first_verified_refresh_and_failed_claim_retries") {
                val path=File(targetContext.noBackupFilesDir,"auto-history-restore-binding").apply { delete() }
                val login=Login().apply { failCheck=true }; val store=Sessions().apply { session=AccountSession(6101,"synthetic") }
                val account=manager(login,path,store)
                check(!account.state.value.signedIn && !path.exists())
                login.failCheck=false; account.refresh(); waitFor("verified refresh") { !account.state.value.busy }
                check(account.state.value.signedIn && JSONObject(path.readText()).getString("owner")=="account:6101")
                val broken=File(targetContext.noBackupFilesDir,"auto-history-restore-corrupt").apply { writeText("not-json") }
                val restored=manager(login,broken,Sessions().apply { session=AccountSession(6102,"synthetic") })
                check(!restored.state.value.signedIn && restored.lease()==null)
                broken.delete(); restored.check(); waitFor("restored acceptance retry") { !restored.state.value.busy }
                check(restored.state.value.signedIn && JSONObject(broken.readText()).getString("owner")=="account:6102")
            }
            test("corrupt_or_unwritable_acceptance_never_publishes_login_and_retry_recovers") {
                val db=database("auto-history-failure.db"); runBlocking { db.records().putHistory(row(21)) }
                val corrupt=File(targetContext.noBackupFilesDir,"auto-history-corrupt").apply { writeText("not-json") }
                val parent=File(targetContext.noBackupFilesDir,"auto-history-not-directory").apply { deleteRecursively(); writeText("file") }
                for(path in listOf(corrupt,File(parent,"binding"))) {
                    val login=Login(); val store=Sessions(); val account=manager(login,path,store)
                    val remote=Remote(path); val cloud=CloudSync(account,db.cloud(),remote,scope(),guestMerge=GuestHistoryMerge(path,db.records()))
                    account.login("synthetic","synthetic"); waitFor("failed acceptance settles") { !account.state.value.busy }
                    check(!account.state.value.signedIn && account.lease()==null && store.session==null)
                    runBlocking { cloud.awaitIdle(); check(db.cloud().pending("account:6101").isEmpty()) }
                    check(remote.writes.isEmpty()); cloud.close()
                }
                check(corrupt.readText()=="not-json")
                parent.delete(); parent.mkdirs()
                val path=File(parent,"binding"); val login=Login(); val account=manager(login,path)
                signIn(account,login,6101)
                check(JSONObject(path.readText()).getString("owner")=="account:6101")
            }
            result.putString("result","PASS"); result.putStringArrayList("tests",passed); finish(-1,result)
        } catch(failure:Throwable) { result.putString("failure",failure.javaClass.simpleName+": "+failure.message?.take(300)); result.putStringArrayList("tests",passed); finish(0,result) }
        finally { scopes.forEach { it.cancel() }; databases.forEach { it.close() } }
    }
}
