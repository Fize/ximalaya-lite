package com.shixi.client
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

class SleepFailureUiVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        val out=Bundle();var activity:android.app.Activity?=null
        val dir=File(targetContext.applicationInfo.dataDir,"shared_prefs")
        fun nodes(text:String):List<AccessibilityNodeInfo> {uiAutomation.clearCache();val out=mutableListOf<AccessibilityNodeInfo>();fun walk(n:AccessibilityNodeInfo?){if(n==null)return;if(n.text?.toString()==text||n.contentDescription?.toString()==text)out.add(n);for(i in 0 until n.childCount)walk(n.getChild(i))};walk(uiAutomation.rootInActiveWindow);return out}
        fun waitFor(label:String,test:()->Boolean){val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(test())return;Thread.sleep(100)};error(label)}
        fun click(text:String){waitFor("click $text"){nodes(text).any { n->var p:AccessibilityNodeInfo?=n;var ok=false;while(p!=null&&!ok){if(p.isClickable&&p.isEnabled)ok=p.performAction(AccessibilityNodeInfo.ACTION_CLICK);p=p.parent};ok }}}
        try {
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("startup"){nodes("我的").isNotEmpty() || nodes("OK").isNotEmpty()}; if(nodes("OK").isNotEmpty()) click("OK"); waitFor("home"){nodes("我的").isNotEmpty()};click("我的");click("设置");click("定时停止");click("30分钟后暂停")
            val prefs=targetContext.getSharedPreferences("sleep",0)
            waitFor("timer saved"){prefs.getLong("deadline",0)>SystemClock.elapsedRealtime()+1000000}
            val old=prefs.getLong("deadline",0);check(dir.setWritable(false,true))
            repeat(2) {
                click("定时停止");click("60分钟后暂停")
                waitFor("failure snackbar"){nodes("定时设置失败，请重试").isNotEmpty()}
                check(prefs.getLong("deadline",0)==old){"old deadline changed"}
                nodes("Dismiss").firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(500)
            }
            out.putString("result","PASS failed sleep update shows feedback repeatedly and keeps prior deadline");finish(-1,out)
        }catch(t:Throwable){val labels=mutableListOf<String>();fun walk(n:AccessibilityNodeInfo?) {if(n==null)return;if(n.text!=null || n.contentDescription!=null)labels.add("${n.text}|${n.contentDescription}");for(i in 0 until n.childCount)walk(n.getChild(i))};uiAutomation.clearCache();walk(uiAutomation.rootInActiveWindow);out.putString("visible",labels.joinToString());out.putString("failure",t.stackTraceToString());finish(0,out)}
        finally{dir.setWritable(true,true);activity?.let {runOnMainSync {it.finish()}}}
    }
}
