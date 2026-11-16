package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import com.shixi.protocol.Item
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*

/** Controlled public-metadata responses; no account or listening database writes. */
class DiscoveryVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() = runBlocking {
        val result = Bundle(); val checks = mutableListOf<String>()
        val dir = File(targetContext.cacheDir, "discovery-verification").apply { mkdirs() }
        val scopes = mutableListOf<CoroutineScope>()
        fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes.add(it) }
        suspend fun waitFor(label: String, check: () -> Boolean) { withTimeout(10000) { while (!check()) delay(20) }; checks.add(label) }
        fun rows(prefix: String, page: Int) = (1..20).map { Item((page * 100 + it).toLong(), "$prefix-$page-$it", cover = "https://example.invalid/cover") }
        try {
            dir.deleteRecursively();dir.mkdirs()
            val requests = CopyOnWriteArrayList<Pair<String, Int>>()
            var fail = false
            val file = File(dir, "cache.json")
            val store = DiscoveryStore(file, { t,p -> requests.add(t to p); if (fail) error("offline fixture"); if (t == "英语") emptyList() else rows(t,p) }, scope())
            store.ensure("历史"); waitFor("cold first page", { store.state.value["历史"]?.loaded == true })
            repeat(4) { store.ensure("历史") };delay(150);check(requests.size == 1)
            checks.add("repeat enter sends zero extra requests")
            store.next("历史");waitFor("page two appended", { store.state.value["历史"]?.pages?.size == 2 });check(requests.last() == "历史" to 2)
            store.ensure("儿童");waitFor("uncached topic load", { store.state.value["儿童"]?.loaded == true })
            val count = requests.size;store.ensure("历史");delay(100);check(requests.size == count);check(store.state.value["历史"]!!.rows.size == 40)
            checks.add("topic return preserves pages without reads")
            store.ensure("英语");waitFor("successful empty response cached", { store.state.value["英语"]?.loaded == true });val emptyCount=requests.size;store.ensure("英语");delay(100);check(requests.size==emptyCount)
            fail=true;store.ensure("文学");waitFor("cold failure shown", { store.state.value["文学"]?.error?.isNotEmpty()==true });val failureCount=requests.size;store.ensure("文学");delay(100);check(requests.size==failureCount)
            checks.add("return to cold error waits for explicit retry")
            fail=false;store.refresh("文学");waitFor("cold failure explicit recovery", { store.state.value["文学"]?.loaded==true })
            val before=store.state.value["历史"]!!.rows
            fail=true;store.refresh("历史");waitFor("failed refresh retains cached rows and releases busy", { store.state.value["历史"]?.let { it.error.isNotEmpty() && !it.busy && it.rows==before && it.failedPage==1 } == true })
            fail=false;store.refresh("历史");waitFor("refresh retry replaces pages", { store.state.value["历史"]?.let { it.error.isEmpty() && !it.busy && it.pages.size==1 } == true })
            fail=true;store.next("历史");waitFor("failed more retains content", { store.state.value["历史"]?.let { !it.busy && it.error.isNotEmpty() && it.failedPage==2 && it.pages.size==1 }==true })
            fail=false;store.next("历史");waitFor("more retry requests original page", { store.state.value["历史"]?.let { !it.busy && it.error.isEmpty() && it.pages.size==2 }==true });check(requests.last()=="历史" to 2)
            waitFor("disk committed", { file.exists() && org.json.JSONObject(file.readText()).optJSONObject("历史")?.getJSONArray("pages")?.length()==2 })
            var restoredRequests=0
            val restored=DiscoveryStore(file, { _,_ -> restoredRequests++;error("must use cache") }, scope())
            restored.ensure("历史");restored.ensure("英语");waitFor("new store restores cached pages and empty topic", { restored.state.value["历史"]?.pages?.size==2 && restored.state.value["英语"]?.loaded==true });delay(100);check(restoredRequests==0)
            checks.add("process-style disk restore makes zero requests")
            // Ignore cancellation inside the slow source: the store must still reject the obsolete result.
            val gate=CompletableDeferred<Unit>();var historyCalls=0
            val race=DiscoveryStore(File(dir,"race.json"), { t,p -> if(t=="历史" && ++historyCalls==1) withContext(NonCancellable) { gate.await();rows("old",p) } else rows("new-$t",p) },scope())
            race.ensure("历史");waitFor("slow request started", { historyCalls==1 });race.refresh("历史");waitFor("new refresh wins", { race.state.value["历史"]?.rows?.firstOrNull()?.title?.startsWith("new-")==true });gate.complete(Unit);delay(150);check(race.state.value["历史"]!!.rows.first().title.startsWith("new-"));check(!race.state.value["历史"]!!.busy)
            checks.add("cancelled late response cannot overwrite newer refresh")
            race.ensure("儿童");waitFor("next topic usable after cancellation", { race.state.value["儿童"]?.loaded==true })
            val repeated = DiscoveryStore(File(dir,"repeated.json"), { t,_ -> rows(t,1) },scope())
            repeated.ensure("历史");waitFor("repeated first page", { repeated.state.value["历史"]?.loaded==true })
            repeated.next("历史");waitFor("repeat-only page stops", { repeated.state.value["历史"]?.let { it.pages.size==2 && !it.busy && !it.more }==true })
            check(repeated.state.value["历史"]!!.rows.size==20)
            checks.add("repeat-only page ends automatic pagination without duplicates")
            val corrupt=File(dir,"corrupt.json").apply { writeText("{broken") }
            val recovery=DiscoveryStore(corrupt, { t,p -> rows(t,p) },scope());recovery.ensure("科学");waitFor("corrupt cache cold-load recovery", { recovery.state.value["科学"]?.loaded==true })
            result.putString("result","PASS");result.putString("checks",checks.toString());result.putString("requests",requests.toString());finish(-1,result)
        } catch(e:Throwable) { result.putString("failure",e.stackTraceToString());result.putString("checks",checks.toString());finish(0,result) }
        finally { scopes.forEach { it.cancel() };dir.deleteRecursively() }
    }
}
