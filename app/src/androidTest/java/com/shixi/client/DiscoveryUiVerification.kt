package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import com.shixi.protocol.Item
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

class DiscoveryUiVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle();val calls=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val file=File(targetContext.cacheDir,"discovery-ui-test.json").apply { delete() }
        val field=DiscoveryStore::class.java.getDeclaredField("instance").apply { isAccessible=true }
        val original=field.get(null)
        val store=DiscoveryStore(file,{ _,_ -> calls.incrementAndGet();listOf(Item(257813,"缓存验证专辑",cover="")) },scope)
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
            result.putString("result","PASS native Activity: both top search origins/back, tab and album back zero requests; one real pull one refresh; topic cache reuse")
            result.putInt("requests",calls.get());finish(-1,result)
        } catch(e:Throwable) { result.putString("failure",e.stackTraceToString());result.putInt("requests",calls.get());finish(0,result) }
        finally { activity?.let { runOnMainSync { it.finish() } };scope.cancel();field.set(null,original);file.delete() }
    }
}
