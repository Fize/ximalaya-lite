package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.shixi.protocol.Item
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AlbumQueueVerification : Instrumentation() {
    private var phase = "model"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "model"; start() }
    private fun <T> main(block: () -> T): T {
        var result: T? = null; var error: Throwable? = null
        runOnMainSync { try { result = block() } catch(t: Throwable) { error=t } }
        error?.let { throw it }; @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun waitFor(label: String, block: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while (!block() && System.nanoTime()<end) Thread.sleep(20)
        check(block()) { label }
    }
    private fun service() {
        val out=Bundle();var controller:androidx.media3.session.MediaController?=null
        try {
            val future=main { androidx.media3.session.MediaController.Builder(targetContext,androidx.media3.session.SessionToken(targetContext,android.content.ComponentName(targetContext,PlaybackService::class.java))).buildAsync() }
            val player=future.get(20,TimeUnit.SECONDS);controller=player
            waitFor("restored storage") { main { player.sessionExtras.getBoolean("storageReady") } }
            val savedFile=File(targetContext.cacheDir,"album-service-checkpoint")
            if(phase=="sleep") {
                val api=AccountContentApi(targetContext);val rows=api.tracks(3544,1)
                val entered=CountDownLatch(1);val release=CountDownLatch(1)
                val service=main { PlaybackService.active!! }
                val albumQueue=main { service.javaClass.getDeclaredField("albumQueue").apply {isAccessible=true}.get(service) as AlbumPlaybackQueue }
                val fetch=albumQueue.javaClass.getDeclaredField("fetch").apply {isAccessible=true}
                val original=fetch.get(albumQueue)
                try {
                    main { fetch.set(albumQueue, { album:Long,page:Int -> entered.countDown();release.await(45,TimeUnit.SECONDS);api.tracks(album,page) })
                        player.setMediaItems(listOf(PlaybackItems.mediaItem(rows.last())),0,0);player.prepare();player.play() }
                    check(entered.await(5,TimeUnit.SECONDS))
                    waitFor("sleep scenario audio ready") { main {player.isPlaying && player.duration>10000 && player.currentPosition>500} }
                    main {player.seekTo(player.duration-1000)}
                    waitFor("sleep scenario waiting for page") {main {player.playbackState==Player.STATE_ENDED} }
                    main {service.stopForSleep()};release.countDown()
                    waitFor("late page after sleep settles") { main {player.mediaItemCount>=40 && !player.sessionExtras.getBoolean("albumLoading")} }
                    main {check(!player.playWhenReady);check(PlaybackItems.item(player.currentMediaItem!!)!!.id==rows.last().id)}
                    out.putString("result","PASS actual service sleep stop during delayed catalogue cancels automatic continuation; late page cannot resume or skip")
                } finally {release.countDown();main {fetch.set(albumQueue,original)}}
            } else if(phase=="restore") {
                val saved=savedFile.readText().split(',');val id=saved[0].toLong();val pos=saved[1].toLong()
                main { check(PlaybackItems.item(player.currentMediaItem!!)!!.id==id);check(!player.playWhenReady);check(kotlin.math.abs(player.currentPosition-pos)<1500);player.play() }
                waitFor("restored album plays and rebuilds") { main { player.isPlaying && player.mediaItemCount>=20 } }
                main { check((0 until player.mediaItemCount).all { PlaybackItems.item(player.getMediaItemAt(it))!!.albumId==3544L });check(player.currentPosition>=pos);player.pause() }
                out.putString("result","PASS restart retains current album/episode/breakpoint, paused restoration, explicit play rebuilds album and resumes real audio")
            } else {
                val api=AccountContentApi(targetContext);val page2=api.tracks(3544,2);val page3=api.tracks(3544,3)
                check(page2.size==20 && page3.isNotEmpty())
                main { player.setMediaItems(listOf(PlaybackItems.mediaItem(page2[8])),0,0);player.prepare();player.play() }
                waitFor("actual service audio and catalogue") { main { player.isPlaying && player.currentPosition>800 && player.mediaItemCount>=20 } }
                main { check((0 until player.mediaItemCount).all { PlaybackItems.item(player.getMediaItemAt(it))!!.albumId==3544L }) }
                fun command(action:String,direction:Int)=main { player.sendCustomCommand(androidx.media3.session.SessionCommand("com.shixi.client.ALBUM",Bundle.EMPTY),Bundle().apply {putString("action",action);putInt("direction",direction)}) }
                command("load",1).get(10,TimeUnit.SECONDS)
                waitFor("next real catalogue page") { main { (0 until player.mediaItemCount).any { PlaybackItems.item(player.getMediaItemAt(it))!!.id==page3[0].id } } }
                main {
                    val last=(0 until player.mediaItemCount).first { PlaybackItems.item(player.getMediaItemAt(it))!!.id==page2.last().id }
                    player.seekTo(last,0);player.seekToNextMediaItem();check(PlaybackItems.item(player.currentMediaItem!!)!!.id==page3[0].id)
                    player.seekToPreviousMediaItem();check(PlaybackItems.item(player.currentMediaItem!!)!!.id==page2.last().id)
                }
                main {
                    // System previous/next commands must select episodes even after 3 seconds.
                    player.seekToNext(); player.seekTo(7000); player.seekToPrevious()
                    check(PlaybackItems.item(player.currentMediaItem!!)!!.id==page2.last().id)
                }
                waitFor("previous item decoded") { main { player.isPlaying && player.duration>5000 } }
                main { player.seekTo(player.duration-2000) }
                waitFor("real natural page transition") { main { PlaybackItems.item(player.currentMediaItem!!)!!.id==page3[0].id && player.isPlaying && player.currentPosition>1000 } }
                main {
                    val service=PlaybackService.active!!
                    val engine=service.javaClass.getDeclaredField("engine").apply {isAccessible=true}.get(service) as ExoPlayer
                    check((engine.audioDecoderCounters?.renderedOutputBufferCount?:0)>0)
                    out.putInt("renderedBuffers",engine.audioDecoderCounters?.renderedOutputBufferCount?:0)
                    player.pause()
                }
                val pause=main { PlaybackItems.item(player.currentMediaItem!!)!!.id to player.currentPosition }
                main { player.sendCustomCommand(androidx.media3.session.SessionCommand("com.shixi.client.SAVE",Bundle.EMPTY),Bundle.EMPTY) }.get(10,TimeUnit.SECONDS)
                waitFor("snapshot persisted") { main { player.sessionExtras.getLong("savedRevision")>=player.sessionExtras.getLong("snapshotRevision") } }
                savedFile.writeText("${pause.first},${pause.second}")
                out.putString("result","PASS actual service free audio, canonical album pages, MediaController previous/next across page, natural page transition and durable checkpoint")
            }
            finish(-1,out)
        } catch(t:Throwable) { out.putString("failure",t.stackTraceToString());finish(0,out) }
        finally { controller?.let {main {it.pause();it.release()} } }
    }
    override fun onStart() {
        if(phase!="model") {service();return}

        val result=Bundle();val checks=arrayListOf<String>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var engine:ExoPlayer?=null;var queue:AlbumPlaybackQueue?=null
        try {
            // Two-second local PCM allows actual decoder/natural transitions without a content account.
            val pcm=ByteArray(32000);val samples=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            repeat(16000) { samples.putShort((kotlin.math.sin(it*2*Math.PI*440/8000)*2000).toInt().toShort()) }
            val wave=ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            wave.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
            val file=File(targetContext.cacheDir,"album-verification.wav");file.writeBytes(wave.array())
            val fail=AtomicBoolean(false);var identity=0
            var hold:CountDownLatch?=null;var entered:CountDownLatch?=null
            val calls=java.util.Collections.synchronizedList(mutableListOf<Pair<Long,Int>>())
            fun rows(album:Long,page:Int)=if(page>3 || album==6L && page>1) emptyList() else (1..(if(page==3) 3 else 20)).map { n->Item(album*1000+(page-1)*20+n,"节目 ${(page-1)*20+n}",true,album,page=page) }
            main {
                val source=ResolvingDataSource.Factory(DefaultDataSource.Factory(targetContext)) { it.withUri(android.net.Uri.fromFile(file)) }
                engine=ExoPlayer.Builder(targetContext).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
                queue=AlbumPlaybackQueue(engine!!,scope,{identity},{album,page->
                    calls.add(album to page);entered?.countDown();hold?.await(5,TimeUnit.SECONDS)
                    if(fail.get()) throw java.io.IOException("fixture offline")
                    rows(album,page)
                },{})
                engine!!.addListener(object:Player.Listener {
                    override fun onPlayWhenReadyChanged(ready:Boolean,reason:Int) { if(!ready) queue!!.cancelIntent() }
                    override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int) { queue!!.prefetch() }
                    override fun onPlaybackStateChanged(state:Int) { if(state==Player.STATE_ENDED) queue!!.ended() }
                })
                engine!!.setMediaItems(listOf(PlaybackItems.mediaItem(rows(1,2)[9])))
                queue!!.reset();queue!!.ensure()
            }
            waitFor("middle page loaded") { main { queue!!.ready && !queue!!.loading } }
            main { check(engine!!.mediaItemCount==20);check(engine!!.currentMediaItem!!.mediaId=="1030:1:2");queue!!.more(-1) }
            waitFor("previous page loaded") { main { engine!!.mediaItemCount==40 && !queue!!.loading } }
            main { check(engine!!.currentMediaItem!!.mediaId=="1030:1:2");queue!!.more(1) }
            waitFor("final page loaded") { main { engine!!.mediaItemCount==43 && !queue!!.loading } }
            main {
                check(!queue!!.before && !queue!!.after)
                check((0 until 43).map { PlaybackItems.item(engine!!.getMediaItemAt(it))!!.id }==(1001L..1043L).toList())
                engine!!.seekTo(19,0);queue!!.move(1);check(engine!!.currentMediaItem!!.mediaId=="1021:1:2")
                queue!!.move(-1);check(engine!!.currentMediaItem!!.mediaId=="1020:1:1")
            }
            checks.add("ascending single-album window; prepend preserves current; both cross-page directions; true final boundary")
            main { engine!!.seekTo(42,0);queue!!.move(1);check(engine!!.currentMediaItemIndex==42);engine!!.seekTo(0,0);queue!!.move(-1);check(engine!!.currentMediaItemIndex==0) }
            checks.add("first/last episode never wraps or changes album")
            main { engine!!.setMediaItems(listOf(PlaybackItems.mediaItem(rows(2,1)[9])));queue!!.reset();fail.set(true);queue!!.ensure() }
            waitFor("visible load failure") { main { queue!!.issue.isNotEmpty() && !queue!!.loading } }
            main { check(engine!!.currentMediaItem!!.mediaId=="2010:2:1");fail.set(false);queue!!.retry() }
            waitFor("retry current album") { main { queue!!.ready && !queue!!.loading } }
            checks.add("load failure retains selected episode; retry restores usable album list")
            hold=CountDownLatch(1);entered=CountDownLatch(1)
            main { queue!!.more(1) };check(entered!!.await(5,TimeUnit.SECONDS))
            main { engine!!.setMediaItems(listOf(PlaybackItems.mediaItem(rows(3,1)[9])));queue!!.reset() }
            hold!!.countDown();hold=null;entered=null
            main { queue!!.ensure() }
            waitFor("new album usable after cancellation") { main { queue!!.ready && !queue!!.loading } }
            main { check((0 until engine!!.mediaItemCount).all { PlaybackItems.item(engine!!.getMediaItemAt(it))!!.albumId==3L }) }
            checks.add("delayed previous-album response rejected; new album loads after cancellation")
            hold=CountDownLatch(1);entered=CountDownLatch(1)
            main { queue!!.more(1) };check(entered!!.await(5,TimeUnit.SECONDS));main { identity++ };hold!!.countDown();hold=null;entered=null
            waitFor("identity-invalidated request releases busy") { main { !queue!!.loading } }
            main { check(engine!!.mediaItemCount==20);queue!!.retry() }
            waitFor("new identity retry works") { main { engine!!.mediaItemCount>=40 && !queue!!.loading } }
            checks.add("identity change rejects stale page without locking subsequent retry")
            main { engine!!.seekTo(19,1400);engine!!.prepare();engine!!.play() }
            waitFor("natural audio crosses page") { main { engine!!.currentMediaItem!!.mediaId=="3021:3:2" && engine!!.isPlaying && engine!!.currentPosition>100 } }
            main { check((engine!!.audioDecoderCounters?.renderedOutputBufferCount?:0)>0);engine!!.pause() }
            checks.add("actual PCM output and natural page-boundary transition")
            // Delayed next page at natural end must resume, unless the user pauses first.
            main { engine!!.setMediaItems(listOf(PlaybackItems.mediaItem(rows(4,1)[19])));queue!!.reset() }
            hold=CountDownLatch(1);entered=CountDownLatch(1)
            main { queue!!.ensure() };check(entered!!.await(5,TimeUnit.SECONDS))
            main { engine!!.seekTo(0,1500);engine!!.prepare();engine!!.play() }
            waitFor("waiting at natural end") { main { engine!!.playbackState==Player.STATE_ENDED } }
            hold!!.countDown();hold=null;entered=null
            waitFor("late page automatically continues") { main { engine!!.currentMediaItem!!.mediaId=="4021:4:2" && engine!!.isPlaying } }
            main { engine!!.pause() }
            checks.add("late catalogue response continues natural end into next album episode")
            main { engine!!.setMediaItems(listOf(PlaybackItems.mediaItem(rows(5,1)[19])));queue!!.reset() }
            hold=CountDownLatch(1);entered=CountDownLatch(1)
            main { queue!!.ensure() };check(entered!!.await(5,TimeUnit.SECONDS))
            main { engine!!.seekTo(0,1500);engine!!.prepare();engine!!.play() }
            waitFor("second delayed end") { main { engine!!.playbackState==Player.STATE_ENDED } }
            main { engine!!.pause();check(!queue!!.ended()) };hold!!.countDown();hold=null;entered=null
            waitFor("paused delayed load settles") { main { queue!!.ready && !queue!!.loading && engine!!.mediaItemCount>=40 } }
            main { check(!engine!!.playWhenReady);check(PlaybackItems.item(engine!!.currentMediaItem!!)!!.id==5020L) }
            checks.add("pause while waiting cancels auto-advance; late page cannot resume or skip")
            main { engine!!.setMediaItems(listOf(PlaybackItems.mediaItem(rows(6,1).last())));queue!!.reset() }
            hold=CountDownLatch(1);entered=CountDownLatch(1)
            main { queue!!.ensure() };check(entered!!.await(5,TimeUnit.SECONDS))
            main { engine!!.seekTo(0,1500);engine!!.prepare();engine!!.play() }
            waitFor("full last-page end") { main {engine!!.playbackState==Player.STATE_ENDED} }
            hold!!.countDown();hold=null;entered=null
            waitFor("empty next page stops playback intent") {main {!queue!!.loading && !queue!!.after && !engine!!.playWhenReady} }
            main {check(PlaybackItems.item(engine!!.currentMediaItem!!)!!.id==6020L)}
            checks.add("exactly full last page plus empty following page ends paused without wrapping")
            result.putString("result","PASS")
        } catch(t:Throwable) { result.putString("result","FAIL");result.putString("failure",t.stackTraceToString()) }
        finally { main { queue?.close();engine?.release() };scope.cancel() }
        result.putStringArrayList("checks",checks);finish(if(result.getString("result")=="PASS") -1 else 0,result)
    }
}
