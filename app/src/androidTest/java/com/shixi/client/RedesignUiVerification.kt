package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import com.shixi.protocol.Item
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

class RedesignUiVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle();val calls=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var fail=false
        val file=File(targetContext.cacheDir,"redesign-ui-test.json").apply { delete() }
        val field=DiscoveryStore::class.java.getDeclaredField("instance").apply { isAccessible=true }
        val original=field.get(null)
        val store=DiscoveryStore(file,{ _,_ -> calls.incrementAndGet();if(fail)throw java.io.IOException("fixture offline");listOf(Item(257813,"缓存验证专辑",cover="")) },scope)
        var activity:ComponentActivity?=null
        fun nodes(text:String):List<AccessibilityNodeInfo> {
            if(android.os.Build.VERSION.SDK_INT>=33) uiAutomation.clearCache()
            val found=mutableListOf<AccessibilityNodeInfo>()
            fun visit(n:AccessibilityNodeInfo?) { if(n==null)return;if(n.text?.toString()==text || n.contentDescription?.toString()==text)found.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
            visit(uiAutomation.rootInActiveWindow);return found
        }
        fun waitFor(label:String,test:()->Boolean) { val end=SystemClock.uptimeMillis()+15000;while(!test()&&SystemClock.uptimeMillis()<end)Thread.sleep(50);check(test()){label} }
        fun click(text:String) { var done=false;waitFor(text) { if(!done)for(n in nodes(text)) { var p:AccessibilityNodeInfo?=n;while(p!=null) { if(p.isClickable&&p.isEnabled&&p.performAction(AccessibilityNodeInfo.ACTION_CLICK)){done=true;break};p=p.parent };if(done)break };done } }
        fun back() { runOnMainSync { activity!!.onBackPressedDispatcher.onBackPressed() };Thread.sleep(450) }
        try {
            field.set(null,store)
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            waitFor("home ready"){nodes("继续收听").isNotEmpty()}
            click("搜索专辑");waitFor("home top search"){nodes("搜索").isNotEmpty()};back();waitFor("back home"){nodes("继续收听").isNotEmpty()}
            click("发现");waitFor("first cached response"){nodes("缓存验证专辑").isNotEmpty()};check(calls.get()==1)
            click("收听");waitFor("home"){nodes("继续收听").isNotEmpty()};click("发现");waitFor("cached return"){nodes("缓存验证专辑").isNotEmpty()};check(calls.get()==1)
            click("缓存验证专辑");waitFor("actual album details"){nodes("节目").isNotEmpty()};back();waitFor("album back"){nodes("缓存验证专辑").isNotEmpty()};check(calls.get()==1)
            click("搜索专辑");waitFor("discover top search"){nodes("搜索").isNotEmpty()};back();waitFor("search back to discover"){nodes("缓存验证专辑").isNotEmpty()};check(calls.get()==1)
            val metrics=targetContext.resources.displayMetrics;val start=SystemClock.uptimeMillis();val x=metrics.widthPixels*.55f;val y=metrics.heightPixels*.42f;val delta=metrics.heightPixels*.32f
            fun event(action:Int,t:Long,yy:Float) { val e=MotionEvent.obtain(start,t,action,x,yy,0);uiAutomation.injectInputEvent(e,true);e.recycle() }
            event(MotionEvent.ACTION_DOWN,start,y)
            for(i in 1..20){Thread.sleep(16);event(MotionEvent.ACTION_MOVE,SystemClock.uptimeMillis(),y+delta*i/20)}
            event(MotionEvent.ACTION_UP,SystemClock.uptimeMillis(),y+delta)
            waitFor("real pull gesture triggers refresh"){calls.get()==2};Thread.sleep(600);check(calls.get()==2)
            click("儿童");waitFor("uncached new topic"){calls.get()==3 && !store.state.value.getValue("儿童").busy};click("历史");waitFor("topic back"){nodes("缓存验证专辑").isNotEmpty()};Thread.sleep(300);check(calls.get()==3)
            fail=true
            runOnMainSync { store.refresh("历史") }
            waitFor("refresh failure retains cache") { nodes("加载失败，已保留上次内容").isNotEmpty() && nodes("缓存验证专辑").isNotEmpty() }
            check(calls.get()==4)
            fail=false;click("重试");waitFor("failed refresh recovers") { calls.get()==5 && !store.state.value.getValue("历史").busy && store.state.value.getValue("历史").error.isEmpty() }
            click("我的");waitFor("library tabs") { nodes("订阅").isNotEmpty() && nodes("历史").isNotEmpty() }
            check(nodes("立即同步").isEmpty() && nodes("合并本机历史…").isEmpty())
            click("历史");click("设置");waitFor("settings") { nodes("存储与关于").isNotEmpty() }
            check(nodes("运行信息").isEmpty() && nodes("技术诊断").isEmpty())
            back();waitFor("library preserved") { nodes("我的").isNotEmpty() }
            click("收听");waitFor("listening home") { nodes("继续收听").isNotEmpty() }
            val currentPlaying=mutableStateOf(true)
            val remote=CloudHistoryRow("account:fixture",91,92,"云历史暂停验证","",1000,60000,false,1,2)
            val cloud=mutableStateOf(CloudState(owner="account:fixture",history=listOf(remote),message="服务器核对完成"))
            val locateCalls=AtomicInteger();val toggleCalls=AtomicInteger()
            runOnMainSync { activity!!.setContent { MaterialTheme(colorScheme=clientColors(false),typography=ClientTypography) {
                LibraryScreen(local=ListeningData(loaded=true),cloud=cloud.value,cloudVisible=true,
                    onSettings={},onAccount={},onQueue={},onSync={},onAlbum={},onToggle={},onHistory={},
                    onCloudHistory={locateCalls.incrementAndGet()},currentId=91,playing=currentPlaying.value,initialFilter="历史",
                    onCurrentPlayback={toggleCalls.incrementAndGet();currentPlaying.value=!currentPlaying.value})
            } } }
            waitFor("remote pause visible") { nodes("暂停 云历史暂停验证").isNotEmpty() }
            click("暂停 云历史暂停验证");waitFor("remote pauses without lookup") { !currentPlaying.value && toggleCalls.get()==1 }
            check(locateCalls.get()==0)
            click("播放 云历史暂停验证");waitFor("remote resumes without old server seek") { currentPlaying.value && toggleCalls.get()==2 }
            check(locateCalls.get()==0)
            for(message in listOf("同步服务 HTTP 500","同步服务 HTTP 503","同步服务业务拒绝","同步响应格式不符","历史签名未生成")) {
                runOnMainSync { cloud.value=cloud.value.copy(message=message) }
                waitFor("visible sync error: $message") { nodes("同步失败，已保留已有内容").isNotEmpty() && nodes("云历史暂停验证").isNotEmpty() }
                check(syncViewState(cloud.value)==SyncViewState.ERROR)
            }
            runOnMainSync { cloud.value=cloud.value.copy(message="服务器核对完成") }
            waitFor("sync recovery clears error and keeps data") { nodes("同步失败，已保留已有内容").isEmpty() && nodes("云历史暂停验证").isNotEmpty() }
            check(syncViewState(cloud.value)==SyncViewState.SYNCED)
            check(syncViewState(cloud.value.copy(message="账号记录已读取，尚未核对服务器"))==SyncViewState.UNCHECKED)
            check(syncViewState(cloud.value.copy(message="2项尚未由服务器确认，可稍后重试",pending=2))==SyncViewState.PENDING)
            check(syncViewState(cloud.value.copy(message="登录已失效，请重新登录；待同步记录仍属于原账号"))==SyncViewState.EXPIRED)
            result.putString("result","PASS native redesigned Activity: top searches/back; tab/album cache reuse; actual pull; topic cache; failed refresh preserves content; retry recovers; library tabs; diagnostics removed from settings; remote history pause/resume zero lookup; five sync failure kinds remain visible and successful recovery clears error")
            result.putInt("requests",calls.get());finish(-1,result)
        } catch(e:Throwable) { result.putString("failure",e.stackTraceToString());result.putInt("requests",calls.get());finish(0,result) }
        finally { activity?.let { runOnMainSync { it.finish() } };scope.cancel();field.set(null,original);file.delete() }
    }
}
