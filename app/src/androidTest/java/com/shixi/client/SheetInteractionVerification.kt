package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.*
import androidx.media3.exoplayer.ExoPlayer
import com.shixi.protocol.Item
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SheetInteractionVerification : Instrumentation() {
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    private fun <T> main(block:()->T):T {var v:T?=null;var e:Throwable?=null;runOnMainSync {try{v=block()}catch(t:Throwable){e=t}};e?.let{throw it};@Suppress("UNCHECKED_CAST") return v as T}
    private fun waitFor(label:String,block:()->Boolean) {val end=System.nanoTime()+20_000_000_000;while(!block()&&System.nanoTime()<end)Thread.sleep(30);check(block()){label}}
    private fun nodes(label:String):List<AccessibilityNodeInfo> {
        if(android.os.Build.VERSION.SDK_INT>=33)uiAutomation.clearCache()
        val out=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?) {if(n==null)return;if(n.text?.toString()==label||n.contentDescription?.toString()==label)out.add(n);repeat(n.childCount){visit(n.getChild(it))}}
        visit(uiAutomation.rootInActiveWindow);return out
    }
    private fun click(label:String) {
        var clicked=false
        waitFor("click $label") {
            if(!clicked) clicked=nodes(label).any {n->var a:AccessibilityNodeInfo?=n;var ok=false;while(a!=null&&!ok){if(a.isClickable)ok=a.performAction(AccessibilityNodeInfo.ACTION_CLICK);a=a.parent};ok}
            clicked
        }
    }

    override fun onStart() {
        val out=Bundle();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val af=AccountManager::class.java.getDeclaredField("instance").apply {isAccessible=true};val sf=CloudSync::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val oldA=af.get(null);val oldS=sf.get(null);var controller:MediaController?=null;var activity:android.app.Activity?=null
        val release=CountDownLatch(1);val entered=CountDownLatch(1)
        val db=ListeningDatabase.open(targetContext,"sheet-ui-fixture.db")
        try {
            val session=AccountSession(9922,"synthetic-sheet-only")
            val gateway=object:AccountGateway {
                override fun send(mobile:String){error("no SMS")};override fun login(mobile:String,code:String)=session
                override fun check(s:AccountSession):Boolean {entered.countDown();release.await(30,TimeUnit.SECONDS);return true}
                override fun refresh(s:AccountSession)=AccountSession(s.uid,"synthetic-new")
            }
            val manager=AccountManager(gateway,object:SessionStore {override fun read()=session;override fun write(s:AccountSession){};override fun clear(){}},targetContext.getSharedPreferences("sheet-only",0),0,scope)
            val sync=CloudSync(manager,db.cloud(),object:CloudGateway {
                override fun read(lease:AccountLease)=CloudSnapshot(emptyList(),emptyList())
                override fun progress(lease:AccountLease,row:CloudHistoryRow){error("no upload")}
                override fun subscription(lease:AccountLease,row:CloudSubscriptionRow){error("no subscription")}
            },scope)
            af.set(null,manager);sf.set(null,sync);check(entered.await(5,TimeUnit.SECONDS))
            controller=main {MediaController.Builder(targetContext,SessionToken(targetContext,android.content.ComponentName(targetContext,PlaybackService::class.java))).buildAsync()}.get(20,TimeUnit.SECONDS)
            val player=controller!!;waitFor("storage ready"){main {player.sessionExtras.getBoolean("storageReady")}}
            val service=main {PlaybackService.active!!}
            val q=main {service.javaClass.getDeclaredField("albumQueue").apply{isAccessible=true}.get(service) as AlbumPlaybackQueue}
            val fetch=q.javaClass.getDeclaredField("fetch").apply{isAccessible=true}
            var fail=false
            main {fetch.set(q,{album:Long,page:Int->if(fail)throw java.io.IOException("fixture offline");(1..20).map{Item((page-1)*20L+it,"测试节目${(page-1)*20+it}",true,album,page=page)}})
                player.setMediaItems(listOf(PlaybackItems.mediaItem(Item(10,"测试节目10",true,777,page=1))),0,0) }
            release.countDown();waitFor("login restoration complete"){manager.state.value.signedIn&&!manager.state.value.busy}
            waitFor("idle account update applied"){main {!player.playWhenReady&&player.sessionExtras.getString("playbackIssue").orEmpty().isEmpty()}}
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("选集")
            waitFor("current episode visible") {nodes("测试节目10").isNotEmpty()&&nodes("正在收听").isNotEmpty()}
            check(nodes("暂时无法播放，请重试").isEmpty())
            val heading=nodes("选集").firstOrNull {it.text?.toString()=="选集"}!!
            val bounds=android.graphics.Rect();heading.getBoundsInScreen(bounds)
            check(bounds.top<uiAutomation.takeScreenshot()!!.height/2){"sheet starts half expanded"}
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("one back dismisses sheet") {nodes("选集").none {it.text?.toString()=="选集"} && nodes("Close sheet").isEmpty() && nodes("收听").isNotEmpty()}
            check(nodes("收听").isNotEmpty())
            val engine=main {service.javaClass.getDeclaredField("engine").apply{isAccessible=true}.get(service) as ExoPlayer}
            main {
                engine.setMediaItem(PlaybackItems.mediaItem(Item(10,"测试节目10",true,777,page=1)).buildUpon().setUri("file:///nonexistent-sheet-fixture.wav").build())
                q.reset();engine.prepare()
            }
            waitFor("actual paused source fails") {main {player.playerError!=null}}
            main {check(!player.playWhenReady);check(player.sessionExtras.getString("playbackIssue").orEmpty().isEmpty())}
            click("选集");waitFor("paused failure catalogue remains usable"){nodes("测试节目1").isNotEmpty() || nodes("测试节目10").isNotEmpty()}
            main {check(PlaybackItems.item(player.currentMediaItem!!)!!.id==10L)}
            check(nodes("暂时无法播放，请重试").isEmpty())
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor("paused failure sheet closes once"){nodes("选集").none{it.text?.toString()=="选集"} && nodes("Close sheet").isEmpty() && nodes("收听").isNotEmpty()}

            // A catalogue error is not a playback error; opening and closing cannot prepare audio.
            val previousSourceError=main {player.playerError}
            main {fail=true;player.setMediaItems(listOf(PlaybackItems.mediaItem(Item(10,"测试节目10",true,777,page=1))),0,0)};click("选集")
            waitFor("catalogue error visible") {nodes("加载失败，点击重试").isNotEmpty()}
            check(nodes("暂时无法播放，请重试").isEmpty());main {check(!player.playWhenReady);check(player.playerError==null || player.playerError==previousSourceError)}
            main {fail=false};click("加载失败，点击重试")
            waitFor("catalogue retry usable") {nodes("测试节目10").isNotEmpty()&&nodes("正在收听").isNotEmpty()}
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor("retry sheet one back exits"){nodes("选集").none{it.text?.toString()=="选集"} && nodes("Close sheet").isEmpty() && nodes("收听").isNotEmpty()}
            main {engine.play()};manager.refresh();waitFor("credential refresh finished"){!manager.state.value.busy}
            waitFor("active intent still interrupted safely"){main {!player.playWhenReady && player.sessionExtras.getString("playbackIssue").orEmpty().contains("账号会话")}}
            main {
                var requested=false
                val fake=java.lang.reflect.Proxy.newProxyInstance(androidx.media3.common.Player::class.java.classLoader,arrayOf(androidx.media3.common.Player::class.java)) {_,method,_->
                    when(method.name) {
                        "getPlayWhenReady"->requested
                        "getCurrentMediaItem"->PlaybackItems.mediaItem(Item(1,"fixture",true,1))
                        "getCurrentPosition"->0L
                        "getPlaybackSuppressionReason"->0
                        "pause"->{requested=false;null}
                        else->null
                    }
                } as androidx.media3.common.Player
                val recovery=PlaybackRecovery(fake,PlaybackResolver({_,_->emptyList()},{error("no resolve")}),android.os.Handler(android.os.Looper.getMainLooper()),{false},{})
                val network=androidx.media3.common.PlaybackException("fixture offline",java.io.IOException(),androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                recovery.onError(network);check(recovery.issue.isEmpty())
                requested=true;recovery.onError(network);check(recovery.issue.contains("网络不可用"))
                recovery.cancel();recovery.onError(androidx.media3.common.PlaybackException("fixture denied",PlaybackAccessException(),androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                check(!requested && recovery.issue.contains("无明确收听权限"));recovery.cancel()
            }
            out.putString("result","PASS idle login restore and catalogue failure never create playback error; sheet opens fully, one Back dismisses, retry works; active credential replacement still stops playback")
            finish(-1,out)
        }catch(t:Throwable){
            val labels=mutableListOf<String>();fun visit(n:AccessibilityNodeInfo?) {if(n==null)return; if(n.text!=null||n.contentDescription!=null)labels.add("${n.text}|${n.contentDescription}");repeat(n.childCount){visit(n.getChild(it))}};visit(uiAutomation.rootInActiveWindow)
            out.putString("visible",labels.joinToString().take(2000));out.putString("failure",t.stackTraceToString());finish(0,out)}
        finally{release.countDown();activity?.let{main{it.finish()}};controller?.let{main{it.pause();it.release()}};af.set(null,oldA);sf.set(null,oldS);scope.cancel();db.close()}
    }
}
