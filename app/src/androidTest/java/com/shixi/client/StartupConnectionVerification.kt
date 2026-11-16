package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import java.net.*
import java.security.*
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Only generated loopback TLS and synthetic identities. No production credentials or real cloud calls. */
class StartupConnectionVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val out=Bundle();val checks=mutableListOf<String>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val gate=CompletableDeferred<Unit>();var activity:ComponentActivity?=null
        fun nodes(label:String):List<AccessibilityNodeInfo> {
            uiAutomation.clearCache();val found=mutableListOf<AccessibilityNodeInfo>()
            fun walk(n:AccessibilityNodeInfo?){if(n==null)return;if(n.text?.toString()==label || n.contentDescription?.toString()==label)found.add(n);for(i in 0 until n.childCount)walk(n.getChild(i))};walk(uiAutomation.rootInActiveWindow);return found
        }
        fun waitFor(label:String,condition:()->Boolean){val until=SystemClock.uptimeMillis()+10000;while(!condition() && SystemClock.uptimeMillis()<until)Thread.sleep(40);check(condition()){label}}
        fun click(label:String){var clicked=false;waitFor(label){nodes(label).forEach { n->var p:AccessibilityNodeInfo?=n;while(!clicked && p!=null){if(p.isClickable)clicked=p.performAction(AccessibilityNodeInfo.ACTION_CLICK);p=p.parent}};clicked}}
        try {
            val store=ListeningStore.get(targetContext);val saved=runBlocking {store.restore().await()};val entered=CountDownLatch(1)
            val f=ListeningStore::class.java.getDeclaredField("work").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST") val work=f.get(store) as Channel<suspend ()->Unit>
            check(work.trySend { entered.countDown();gate.await() }.isSuccess);check(entered.await(5,TimeUnit.SECONDS))
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            waitFor("home visible while restoration blocked"){nodes("继续收听").isNotEmpty()}
            check(!gate.isCompleted && nodes("正在恢复本机记录…").isEmpty())
            val recent=checkNotNull(store.state.value.history.firstOrNull()) { "Cancellation check requires existing emulator history" }
            click("播放 ${recent.title}")
            click("搜索专辑");waitFor("search usable during restore"){nodes("搜索").isNotEmpty()}
            runOnMainSync{activity!!.onBackPressedDispatcher.onBackPressed()};click("我的");waitFor("library usable during restore"){nodes("订阅").isNotEmpty()}
            gate.complete(Unit);waitFor("service restore completes") {
                var ready=false;runOnMainSync {PlaybackService.active?.let { service -> ready=PlaybackService::class.java.getDeclaredField("storageReady").apply {isAccessible=true}.getBoolean(service)}};ready
            }
            runOnMainSync {
                val engine=PlaybackService::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(PlaybackService.active) as androidx.media3.common.Player
                check(!engine.playWhenReady && engine.mediaItemCount==saved.queue.size)
                if(saved.queue.isNotEmpty())check(engine.currentPosition==saved.positionMs)
            }
            checks.add("restored queue/position preserved; leaving pending selection does not autoplay")
            checks.add("home/search/library usable while local restore deliberately blocked")

            val cert=CertificateFactory.getInstance("X.509").generateCertificate(context.assets.open("loopback-cert.der"))
            val key=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(context.assets.open("loopback-key.der").readBytes()))
            val ks=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setKeyEntry("test",key,"test".toCharArray(),arrayOf(cert)) }
            val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(ks,"test".toCharArray())}
            val trust=object:X509TrustManager {override fun getAcceptedIssuers()=emptyArray<java.security.cert.X509Certificate>();override fun checkClientTrusted(c:Array<java.security.cert.X509Certificate>,a:String){};override fun checkServerTrusted(c:Array<java.security.cert.X509Certificate>,a:String){} }
            val ssl=SSLContext.getInstance("TLS").apply{init(km.keyManagers,arrayOf(trust),SecureRandom())}
            val server=ssl.serverSocketFactory.createServerSocket(0,10,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            server.soTimeout=10000;val requests=AtomicInteger();val failures=AtomicInteger();val slow=java.util.concurrent.atomic.AtomicBoolean(false)
            val responder=scope.launch { try { while(true){val accepted=server.accept();scope.launch { runCatching { accepted.use { socket ->
                socket.soTimeout=3000
                val reader=socket.inputStream.bufferedReader();val line0=reader.readLine()?:return@use;while(true){val line=reader.readLine()?:break;if(line.isEmpty())break};requests.incrementAndGet()
                if(slow.get())Thread.sleep(450)
                val body=(if(slow.get() && line0.contains("/album/info")) """{"ret":0,"albumId":34,"vipFreeType":1}"""
                    else if(slow.get()) """{"ret":0,"data":{"list":[{"trackId":12,"title":"loopback","isPaid":true,"vipFreeType":1}]}}"""
                    else """{"ret":0,"data":{"list":[{"trackId":12,"title":"loopback","isPaid":false,"isHoldCopyright":true,"isSample":false}]}}""").toByteArray()
                socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: ${if(slow.get()) "close" else "keep-alive"}\r\n\r\n".toByteArray());socket.outputStream.write(body);socket.outputStream.flush()
                if(!slow.get() && reader.readLine()!=null) { while(true){val line=reader.readLine()?:break;if(line.isEmpty())break};requests.incrementAndGet() } // Drop the reused connection without a response.
            } } } } } catch(_:Exception){} }
            val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(1,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS)
                .sslSocketFactory(ssl.socketFactory,trust).hostnameVerifier { _,_->true }
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                .eventListener(object:EventListener(){override fun connectFailed(call:Call,a:InetSocketAddress,p:Proxy,protocol:Protocol?,e:java.io.IOException){failures.incrementAndGet()}})
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().host("loopback.test").port(server.localPort).build()).build()) }.build()
            val gateway=object:AccountGateway {override fun send(mobile:String){error("no SMS")};override fun login(mobile:String,code:String)=AccountSession(88004,"synthetic");override fun check(session:AccountSession)=true;override fun refresh(session:AccountSession)=session}
            val accounts=AccountManager(gateway,object:SessionStore {override fun read():AccountSession?=null;override fun write(session:AccountSession){};override fun clear(){}},targetContext.getSharedPreferences("startup-network-only",0),0,scope)
            waitFor("account init"){!accounts.state.value.busy}
            try {
                val old=client.newBuilder().connectionPool(ConnectionPool()).retryOnConnectionFailure(false).build()
                val request=Request.Builder().url("https://mobile.ximalaya.com/mobile/v1/album/track/ts-1").build()
                old.newCall(request).execute().use { it.body!!.string() }
                check(runCatching {old.newCall(request).execute().use{it.body!!.string()}}.isFailure)
                check(requests.get()==2)
                val content=AccountContentApi(targetContext,accounts,client)
                check(content.tracks(34).single().id==12L) // Warm a pooled connection.
                val start=SystemClock.elapsedRealtime();check(content.tracks(34).single().id==12L)
                check(requests.get()==5 && SystemClock.elapsedRealtime()-start<5000)
                checks.add("old transport fails reused connection; fixed transport retries on fresh TLS connection in same call")
                val before=requests.get()
                val switched=client.newBuilder().connectionPool(ConnectionPool()).eventListener(object:EventListener(){override fun connectEnd(call:Call,a:InetSocketAddress,p:Proxy,protocol:Protocol?){accounts.logout()}}).build()
                check(runCatching{AccountContentApi(targetContext,accounts,switched).tracks(34)}.exceptionOrNull() is com.shixi.protocol.ContentAccessException)
                check(requests.get()==before)
                checks.add("session change before initial exchange sends zero HTTP requests")
                slow.set(true)
                val startSlow=SystemClock.elapsedRealtime()
                val limited=client.newBuilder().connectionPool(ConnectionPool()).callTimeout(700,TimeUnit.MILLISECONDS).build()
                // The optional album hint may be omitted after timeout; regardless, the
                // complete operation must not receive a fresh 700ms budget for each hop.
                runCatching {AccountContentApi(targetContext,accounts,limited).tracks(34)}
                check(SystemClock.elapsedRealtime()-startSlow<900)
                check(requests.get()==before+2)
                checks.add("multiple metadata hops share one timeout budget")
            } finally {server.close();responder.cancel()}
            out.putString("result","PASS");out.putString("checks",checks.toString());finish(-1,out)
        } catch(e:Throwable){out.putString("failure",e.stackTraceToString());out.putString("checks",checks.toString());finish(0,out)}
        finally {gate.complete(Unit);activity?.let {runOnMainSync{it.finish()}};scope.cancel()}
    }
}
