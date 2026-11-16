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
import kotlinx.coroutines.runBlocking

class ColdLaunchPopupVerification : Instrumentation() {
 private var phase = ""
 override fun onCreate(arguments: Bundle?) { phase=arguments?.getString("phase").orEmpty();super.onCreate(arguments);start() }
 override fun onStart() {
  val result=Bundle(); var controller:MediaController?=null
  fun nodes(label:String):List<AccessibilityNodeInfo>{uiAutomation.clearCache();val out=mutableListOf<AccessibilityNodeInfo>();fun walk(n:AccessibilityNodeInfo?){if(n==null)return;if(n.text?.toString()==label||n.contentDescription?.toString()==label)out.add(n);for(i in 0 until n.childCount)walk(n.getChild(i))};walk(uiAutomation.rootInActiveWindow);return out}
  fun waitFor(label:String,test:()->Boolean){val end=SystemClock.uptimeMillis()+25000;while(SystemClock.uptimeMillis()<end){if(test())return;Thread.sleep(100)};error(label)}
  fun click(label:String){waitFor(label){nodes(label).any { node -> var n:AccessibilityNodeInfo?=node;var done=false;while(n!=null&&!done){if(n.isClickable&&n.isEnabled)done=n.performAction(AccessibilityNodeInfo.ACTION_CLICK);n=n.parent};done } }}
  try {
   val f=MediaController.Builder(targetContext,SessionToken(targetContext,ComponentName(targetContext,PlaybackService::class.java))).buildAsync();val p=f.get(20,TimeUnit.SECONDS);controller=p
   waitFor("storage"){var ready=false;runOnMainSync{ready=p.sessionExtras.getBoolean("storageReady")};ready}
   if(phase=="seed") {
    runOnMainSync{p.setMediaItem(PlaybackItems.mediaItem(Item(222612,"冷启动测试",true,3544,page=1)));p.pause()};Thread.sleep(1500);runBlocking{ListeningStore.get(targetContext).awaitIdle()}
    result.putString("result","PASS stored paused episode")
   } else {
    waitFor("restore") { var id=""; runOnMainSync{id=p.currentMediaItem?.mediaId.orEmpty()};id.startsWith("222612:") }
    startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    waitFor("activity"){nodes("OK").isNotEmpty() || nodes("冷启动测试").isNotEmpty()};if(nodes("OK").isNotEmpty())click("OK")
    waitFor("mini"){nodes("冷启动测试").isNotEmpty()};val rect=android.graphics.Rect();nodes("冷启动测试").first().getBoundsInScreen(rect)
    uiAutomation.executeShellCommand("input tap ${rect.centerX()} ${rect.centerY()}").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
    waitFor("player"){nodes("略过头尾").isNotEmpty()};Thread.sleep(1500);check(nodes("略过片头片尾").isEmpty())
    val start=SystemClock.elapsedRealtime();click("播放");waitFor("playing"){var playing=false;runOnMainSync{playing=p.isPlaying};playing};val elapsed=SystemClock.elapsedRealtime()-start
    result.putString("result","PASS cold start did not open skip; real playback started in ${elapsed}ms")
   }
   controller?.let{runOnMainSync{it.pause();it.release()}};finish(-1,result)
  } catch(t:Throwable){result.putString("failure",t.stackTraceToString());controller?.let{runOnMainSync{it.release()}};finish(0,result)}
 }
}
