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

class DiscoveryPagingVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle();val calls=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val file=File(targetContext.cacheDir,"discovery-ui-test.json").apply { delete() }
        val field=DiscoveryStore::class.java.getDeclaredField("instance").apply { isAccessible=true }
        val original=field.get(null)
        var failNext = true
        val store=DiscoveryStore(file,{ _,page ->
            calls.incrementAndGet()
            delay(100)
            if (page == 2 && failNext) error("offline")
            (1..20).map { Item((page * 100 + it).toLong(), "专辑 $page-$it", cover = "") }
        },scope)
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
            click("发现");waitFor("first page"){nodes("专辑 1-1").isNotEmpty()};check(calls.get()==1)
            fun swipe(up:Boolean) {
                val m=targetContext.resources.displayMetrics;val start=SystemClock.uptimeMillis()
                val x=m.widthPixels*.5f;val y=m.heightPixels*(if(up) .72f else .3f);val delta=m.heightPixels*(if(up) -.45f else .45f)
                fun e(action:Int,yy:Float){val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,yy,0);uiAutomation.injectInputEvent(event,true);event.recycle()}
                e(MotionEvent.ACTION_DOWN,y);for(i in 1..20){Thread.sleep(16);e(MotionEvent.ACTION_MOVE,y+delta*i/20)};e(MotionEvent.ACTION_UP,y+delta);Thread.sleep(200)
            }
            repeat(15) { if(calls.get()==1) swipe(true) }
            repeat(3) { if(nodes("加载失败，点击重试").isEmpty()) swipe(true) }
            waitFor("bottom automatically loads and exposes failure"){nodes("加载失败，点击重试").isNotEmpty()}
            val failed=calls.get();repeat(2){swipe(true)};check(calls.get()==failed){"failure retry loop"}
            failNext=false;click("加载失败，点击重试")
            waitFor("page two recovery"){store.state.value["历史"]?.pages?.size==2};check(calls.get()==failed+1)
            repeat(15) { if(store.state.value["历史"]!!.pages.size==2) swipe(true) }
            waitFor("next bottom automatically pages"){store.state.value["历史"]!!.pages.size>=3}
            val before=calls.get();click("收听");click("发现");Thread.sleep(500);check(calls.get()==before){"return fetched again"}
            repeat(24){swipe(false)}
            val afterPull=calls.get();swipe(false);waitFor("pull refresh"){calls.get()>afterPull}
            check(store.state.value["历史"]!!.pages.size==1)
            result.putString("result","PASS native gestures: automatic bottom paging, no error loop, explicit failure retry, second append, cached return, pull refresh")
            result.putInt("requests",calls.get());finish(-1,result)
        } catch(e:Throwable) { result.putString("failure",e.stackTraceToString());result.putInt("requests",calls.get());finish(0,result) }
        finally { activity?.let { runOnMainSync { it.finish() } };scope.cancel();field.set(null,original);file.delete() }
    }
}
