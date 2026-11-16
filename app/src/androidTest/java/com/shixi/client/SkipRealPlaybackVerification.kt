package com.shixi.client
import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.shixi.protocol.Item
import java.util.concurrent.TimeUnit

class SkipRealPlaybackVerification : Instrumentation() {
 override fun onCreate(args: Bundle?) { super.onCreate(args);start() }
 override fun onStart() {
  val out=Bundle();var p:MediaController?=null;val store=SkipHeadTailStore(targetContext);val previous=store.get(3544)
  fun waitFor(label:String,timeout:Long,check:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(check())return;Thread.sleep(100)};error(label)}
  try {
   check(store.save(3544,SkipHeadTail(25,5)))
   p=MediaController.Builder(targetContext,SessionToken(targetContext,ComponentName(targetContext,PlaybackService::class.java))).buildAsync().get(20,TimeUnit.SECONDS)
   waitFor("storage",20000){var ready=false;runOnMainSync{ready=p!!.sessionExtras.getBoolean("storageReady")};ready}
   runOnMainSync { p!!.setMediaItem(PlaybackItems.mediaItem(Item(222612,"测试节目",true,3544,page=1)));p!!.play() }
   waitFor("real head skip",40000){var ok=false;runOnMainSync { ok=p!!.isPlaying && p!!.currentPosition in 25000L..35000L };ok}
   val policy=SkipHeadTailPolicy();check(policy.target(96000,100000,SkipHeadTail(25,5))==100000L)
   out.putString("result","PASS actual free episode starts after 25 second head skip; tail policy uses seconds")
   finish(-1,out)
  } catch(t:Throwable){out.putString("failure",t.stackTraceToString());finish(0,out)}
  finally {store.save(3544,previous);p?.let{runOnMainSync{it.pause();it.release()}}}
 }
}
