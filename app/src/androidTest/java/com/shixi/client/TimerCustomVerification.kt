package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.shixi.protocol.Item
import java.util.concurrent.TimeUnit

class TimerCustomVerification : Instrumentation() {
 override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
 override fun onStart() {
  val out=Bundle(); var p: MediaController?=null; var activity: android.app.Activity?=null
  fun nodes(label:String):List<AccessibilityNodeInfo> { uiAutomation.clearCache(); val found=mutableListOf<AccessibilityNodeInfo>(); fun walk(n:AccessibilityNodeInfo?){ if(n==null)return; if(n.text?.toString()==label || n.contentDescription?.toString()==label || label=="EDIT" && n.isEditable) found.add(n); for(i in 0 until n.childCount)walk(n.getChild(i)) }; walk(uiAutomation.rootInActiveWindow); return found }
  fun await(label:String,timeout:Long=25000,condition:()->Boolean){ val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)};error(label) }
  fun click(label:String){await(label){nodes(label).any{ node -> var n:AccessibilityNodeInfo?=node;var done=false;while(n!=null&&!done){if(n.isClickable&&n.isEnabled)done=n.performAction(AccessibilityNodeInfo.ACTION_CLICK);n=n.parent};done }}}
  fun scroll(){fun walk(n:AccessibilityNodeInfo?):Boolean{if(n==null)return false;if(n.isScrollable&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;for(i in 0 until n.childCount)if(walk(n.getChild(i)))return true;return false};uiAutomation.clearCache();walk(uiAutomation.rootInActiveWindow);Thread.sleep(300)}
  fun input(value:String){repeat(5){if(nodes("EDIT").isEmpty())scroll()};await("input"){nodes("EDIT").any{it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)})}}}
  try {
   val f=MediaController.Builder(targetContext,SessionToken(targetContext,ComponentName(targetContext,PlaybackService::class.java))).buildAsync();p=f.get(20,TimeUnit.SECONDS)
   await("storage"){var ready=false;runOnMainSync{ready=p!!.sessionExtras.getBoolean("storageReady")};ready}
   val store=SkipHeadTailStore(targetContext);check(store.save(3544,SkipHeadTail()))
   runOnMainSync{p!!.setMediaItem(PlaybackItems.mediaItem(Item(222612,"定时测试节目",true,3544,page=1)));p!!.play()}
   await("real playback",40000){var playing=false;runOnMainSync{playing=p!!.isPlaying};playing}
   activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
   await("mini"){nodes("定时测试节目").isNotEmpty()||nodes("OK").isNotEmpty()};if(nodes("OK").isNotEmpty())click("OK")
   val rect=android.graphics.Rect();nodes("定时测试节目").last().getBoundsInScreen(rect);uiAutomation.executeShellCommand("input tap ${rect.centerX()} ${rect.centerY()}").close()
   click("略过头尾");await("seconds"){nodes("片头：0 秒").isNotEmpty()};click("增加片头秒数");click("增加片头秒数");click("增加片尾秒数");await("head"){nodes("片头：2 秒").isNotEmpty()};await("tail"){nodes("片尾：1 秒").isNotEmpty()};click("保存")
   await("saved"){store.get(3544)==SkipHeadTail(2,1)};check(SkipHeadTailPolicy().target(0,100000,store.get(3544))==2000L)
   click("略过头尾");await("reopen"){nodes("片头：2 秒").isNotEmpty()&&nodes("片尾：1 秒").isNotEmpty()};click("重置");click("保存")
   click("定时关闭");input("121");await("invalid"){nodes("确定").any{n->var p:AccessibilityNodeInfo?=n;while(p!=null&&!p.isClickable)p=p.parent;p!=null&&!p.isEnabled}}
   input("7");repeat(5){if(nodes("确定").isEmpty())scroll()};click("确定")
   val prefs=targetContext.getSharedPreferences("sleep",0);await("seven minute deadline"){prefs.getLong("deadline",0)-SystemClock.elapsedRealtime() in 410000L..420000L}
   await("timer label"){nodes("约7分钟").isNotEmpty()};click("约7分钟");click("关闭定时");await("cancel"){prefs.getLong("deadline",-1)==0L}
   click("定时关闭");input("1");repeat(5){if(nodes("确定").isEmpty())scroll()};click("确定")
   await("one minute timer"){prefs.getLong("deadline",0)>0};await("actual pause",70000){var playing=true;runOnMainSync{playing=p!!.isPlaying};!playing&&prefs.getLong("deadline",-1)==0L}
   out.putString("result","PASS seconds increment, persistence and skip policy; invalid custom timer rejected; 7-minute set/cancel; 1-minute custom timer pauses real playback")
   finish(-1,out)
  }catch(t:Throwable){out.putString("failure",t.stackTraceToString());finish(0,out)}finally{activity?.let{runOnMainSync{it.finish()}};p?.let{runOnMainSync{it.release()}}}
 }
}
