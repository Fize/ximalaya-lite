package com.shixi.client
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
class PlaybackLatencyVerification : Instrumentation() {
 override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
 override fun onStart() { val result=Bundle();try {
  val api=AccountContentApi(targetContext);val timings=mutableListOf<String>()
  repeat(3) { val start=SystemClock.elapsedRealtime();val item=api.tracks(3544,1).first{it.id==222612L};val tracks=SystemClock.elapsedRealtime()-start;val before=SystemClock.elapsedRealtime();api.resolve(item);val address=SystemClock.elapsedRealtime()-before;timings.add("list=${tracks}ms address=${address}ms") }
  result.putString("result",timings.joinToString("; "));finish(-1,result)
 }catch(t:Throwable){result.putString("failure",t.stackTraceToString());finish(0,result)} }
}
