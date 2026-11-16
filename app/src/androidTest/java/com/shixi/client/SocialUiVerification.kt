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

class SocialUiVerification:Instrumentation(){
 override fun onCreate(a:Bundle?){super.onCreate(a);start()}
 override fun onStart(){
  val result=Bundle();var player:MediaController?=null;var activity:android.app.Activity?=null
  fun nodes(label:String):List<AccessibilityNodeInfo>{uiAutomation.clearCache();val list=mutableListOf<AccessibilityNodeInfo>();fun walk(n:AccessibilityNodeInfo?){if(n==null)return;if(n.text?.toString()==label||n.contentDescription?.toString()==label)list.add(n);for(i in 0 until n.childCount)walk(n.getChild(i))};walk(uiAutomation.rootInActiveWindow);return list}
  fun waitFor(label:String,check:()->Boolean){val end=SystemClock.uptimeMillis()+25000;while(SystemClock.uptimeMillis()<end){if(check())return;Thread.sleep(100)};error(label)}
  fun click(label:String){waitFor(label){nodes(label).any{node->var n:AccessibilityNodeInfo?=node;var done=false;while(n!=null&&!done){if(n.isClickable&&n.isEnabled)done=n.performAction(AccessibilityNodeInfo.ACTION_CLICK);n=n.parent};done}}}
  try{
   val f=MediaController.Builder(targetContext,SessionToken(targetContext,ComponentName(targetContext,PlaybackService::class.java))).buildAsync();player=f.get(20,TimeUnit.SECONDS)
   waitFor("storage"){var ready=false;runOnMainSync{ready=player!!.sessionExtras.getBoolean("storageReady")};ready}
   runOnMainSync{player!!.setMediaItem(PlaybackItems.mediaItem(Item(222612,"测试节目",true,3544,page=1)));player!!.pause()}
   activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
   waitFor("home"){nodes("测试节目").isNotEmpty()||nodes("OK").isNotEmpty()};if(nodes("OK").isNotEmpty())click("OK")
   waitFor("mini player"){nodes("测试节目").isNotEmpty()}; val rect=android.graphics.Rect();nodes("测试节目").first().getBoundsInScreen(rect);uiAutomation.executeShellCommand("input tap ${rect.centerX()} ${rect.centerY()}").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() };waitFor("player"){nodes("评论").isNotEmpty()&&nodes("点赞").isNotEmpty()};waitFor("album"){nodes("+ 订阅").isNotEmpty()}
   repeat(12) { index ->
    click("收起"); waitFor("collapsed player"){nodes("测试节目").isNotEmpty() && nodes("略过头尾").isEmpty()}
    val bounds = android.graphics.Rect(); nodes("测试节目").first().getBoundsInScreen(bounds)
    val x = when (index % 3) { 0 -> bounds.left + 3; 1 -> bounds.centerX(); else -> bounds.left - (32 * targetContext.resources.displayMetrics.density).toInt() }
    val y = if (index % 2 == 0) bounds.centerY() else bounds.bottom - 2
    val command = if (index % 4 == 0) "input touchscreen swipe $x $y $x $y 200" else "input tap $x $y"
    uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    waitFor("opened player"){nodes("略过头尾").isNotEmpty()}; Thread.sleep(800)
    check(nodes("略过片头片尾").isEmpty()) { "unexpected skip sheet at mini tap $index" }
   }
   fun screenshot(name:String){ uiAutomation.takeScreenshot().let { bitmap -> java.io.FileOutputStream("/sdcard/Download/$name.png").use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() } }
   screenshot("shixi-player-review")
   val count = checkNotNull(AccountContentApi(targetContext).episodeComments(222612,1).totalCount); val label = if (count >= 10000) "${count / 10000}万+" else count.toString(); waitFor("comment count"){nodes(label).isNotEmpty()}
   click("略过头尾");waitFor("skip initial"){nodes("略过片头片尾").isNotEmpty()}
   uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }; Thread.sleep(700)
   targetContext.startActivity(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));waitFor("return player"){nodes("略过头尾").isNotEmpty()}; check(nodes("略过片头片尾").isEmpty())
   click("略过头尾");waitFor("skip settings"){nodes("略过片头片尾").isNotEmpty()};click("重置");waitFor("skip reset"){nodes("片头：0 秒").isNotEmpty()};click("保存");waitFor("skip closes"){nodes("略过片头片尾").isEmpty()}
   click("评论");waitFor("comments"){nodes("写评论").isNotEmpty()}
   check(nodes("写评论").isNotEmpty());click("写评论");Thread.sleep(2000);screenshot("shixi-comments-keyboard-review");sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(500);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor("one back closes comments"){nodes("写评论").isEmpty()&&nodes("评论").isNotEmpty()}
   result.putString("result","PASS actual player exposes subscribe, like, comment and one-back comments panel");finish(-1,result)
  }catch(t:Throwable){result.putString("failure",t.stackTraceToString());finish(0,result)}finally{activity?.let{runOnMainSync{it.finish()}};player?.let{runOnMainSync{it.release()}}}
 }
}
