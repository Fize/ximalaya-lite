package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.shixi.protocol.Item

class EpisodePagingUiVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out = Bundle(); var activity: ComponentActivity? = null
        fun waitFor(label: String, test: () -> Boolean) { val end=SystemClock.uptimeMillis()+15000; while(SystemClock.uptimeMillis()<end) { if(test()) return; Thread.sleep(80) }; error(label) }
        fun node(text: String): AccessibilityNodeInfo? {
            uiAutomation.clearCache()
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { if(n==null)return null; if(n.text?.toString()==text)return n; for(i in 0 until n.childCount)find(n.getChild(i))?.let{return it}; return null }
            return find(uiAutomation.rootInActiveWindow)
        }
        fun scroll(forward: Boolean) {
            val command=if(forward) "input swipe 600 1400 600 400 450" else "input swipe 600 400 600 1400 450"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            val calls=java.util.Collections.synchronizedList(mutableListOf<Int>()); var failNext=true
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
            runOnMainSync { activity!!.setContent {
                val rows=remember { mutableStateListOf<Item>().apply { addAll((21..40).map { Item(it.toLong(),"节目$it",true,1,page=2) }) } }
                var extras by remember { mutableStateOf(Bundle().apply { putBoolean("albumReady",true); putBoolean("albumBefore",true); putBoolean("albumAfter",true) }) }
                fun next(direction:Int) {
                    calls.add(direction)
                    if(direction<0) { rows.addAll(0,(1..20).map { Item(it.toLong(),"节目$it",true,1,page=1) }); extras=Bundle(extras).apply{putBoolean("albumBefore",false)} }
                    else if(failNext) { failNext=false; extras=Bundle(extras).apply{putString("albumIssue","offline")} }
                    else { rows.addAll((41..43).map { Item(it.toLong(),"节目$it",true,1,page=3) }); extras=Bundle(extras).apply{putString("albumIssue","");putBoolean("albumAfter",false)} }
                }
                MaterialTheme { AlbumEpisodeSheet(rows,25L,extras,::next,{next(1)},{}) }
            } }
            waitFor("current episode") { node("节目25")!=null }
            repeat(8) { if(!calls.contains(-1)) { scroll(false); Thread.sleep(250) } }
            waitFor("automatic previous page") { calls.contains(-1) }
            repeat(14) { if(node("加载失败，点击重试")==null) { scroll(true); Thread.sleep(250) } }
            waitFor("next page failure") { node("加载失败，点击重试")!=null }
            check(calls.count{it==1}==1)
            var n=node("加载失败，点击重试");while(n!=null && !n.isClickable)n=n.parent
            check(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
            waitFor("retry next page") { calls.count{it==1}==2 }
            repeat(4) { if(node("节目43")==null) { scroll(true); Thread.sleep(250) } }
            waitFor("last episode visible") { node("节目43")!=null }
            check(calls.count{it<0}==1 && calls.count{it>0}==2)
            out.putString("result","PASS UI automatic previous and next pages, failure requires explicit retry, no repeated fetch, final item visible")
            finish(-1,out)
        } catch(t:Throwable) { val labels=mutableListOf<String>(); fun walk(n:AccessibilityNodeInfo?) { if(n==null)return; val rect=android.graphics.Rect(); n.getBoundsInScreen(rect); if(n.text!=null || n.isScrollable) labels.add("${n.text}:$rect:scroll=${n.isScrollable}"); for(i in 0 until n.childCount)walk(n.getChild(i)) }; uiAutomation.clearCache(); walk(uiAutomation.rootInActiveWindow); out.putString("visible",labels.joinToString());out.putString("failure",t.stackTraceToString());finish(0,out) }
        finally { activity?.let { runOnMainSync { it.finish() } } }
    }
}
