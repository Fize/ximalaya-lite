package com.shixi.client

import android.content.Context
import android.os.Build
import com.shixi.protocol.ContentAccessException
import com.shixi.protocol.ContentApi
import com.shixi.protocol.Item
import com.shixi.protocol.ListeningAccess
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A single exact, verified session owns each operation; media clients never receive credentials. */
class AccountContentApi(private val context: Context,
    private val accounts: AccountManager = AccountManager.get(context),
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build(),
    private val paidDecoder: (String, String) -> Pair<String, String> = { file, ep -> PaidMediaDecoder.decode(context, file, ep) }) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(true).build()
    private val device = context.getSharedPreferences("account-device", Context.MODE_PRIVATE).let {
        it.getString("id", null) ?: UUID.randomUUID().toString().also { id -> check(it.edit().putString("id", id).commit()) }
    }
    private val userAgent = "ting_9.4.74.3(${URLEncoder.encode(Build.MODEL, "UTF-8")},Android${Build.VERSION.SDK_INT})"
    private fun cookie(lease: AccountLease?): String = buildString {
        append("1&_device=android&").append(device).append("&9.4.74.3;")
        append("channel=shixi;impl=").append(context.packageName).append(";osversion=").append(Build.VERSION.SDK_INT).append(';')
        if (lease != null) append("1&_token=").append(lease.session.uid).append('&').append(lease.session.token).append(';')
    }
    private fun <T> operation(authenticated: Boolean = true, block: (ContentApi) -> T): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(client.callTimeoutMillis.toLong().takeIf { it > 0 } ?: 20_000L)
        val identity = accounts.contentIdentity()
        val lease = accounts.lease()
        fun checkOwner() {
            if (accounts.contentIdentity() != identity || lease != null && !accounts.current(lease))
                throw ContentAccessException(ListeningAccess.UNKNOWN, "账号会话已改变，请重新选择节目", sessionChanged = true)
        }
        checkOwner()
        // Recheck before every actual exchange, including a recovered connection.
        // Derived clients share the original pool; the total call timeout is unchanged.
        val ownedClient = client.newBuilder().eventListenerFactory { call ->
            val id = System.identityHashCode(call)
            object : okhttp3.EventListener() {
                private fun event(name: String) = StartupTiming.mark("net_${id}_$name")
                override fun dnsStart(call: okhttp3.Call, domainName: String) = event("dns_start")
                override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) = event("dns_end")
                override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) = event("connect_start")
                override fun secureConnectStart(call: okhttp3.Call) = event("tls_start")
                override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = event("tls_end")
                override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) = event("connection_acquired")
                override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) = event("request_sent")
                override fun responseHeadersStart(call: okhttp3.Call) = event("response_start")
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = event("failed_${ioe.javaClass.simpleName}")
            }
        }.addNetworkInterceptor { chain ->
            checkOwner()
            chain.proceed(chain.request())
        }.build()
        val transport: (okhttp3.Request) -> okhttp3.Response = { request ->
            val url = request.url
            val path = url.encodedPath
            require(request.method == "GET" && url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.fragment == null && (url.host == "mobile.ximalaya.com" &&
                (path == "/v1/track/baseInfo" || path == "/mobile/v1/album/info" || path == "/comment-mobile/v1/track/comment" ||
                    path.matches(Regex("/mobile/others/ca/album/track/[1-9][0-9]*/true/[1-9][0-9]*/20")) ||
                    path.matches(Regex("/mobile/v1/album/track/ts-[0-9]+")) || path.startsWith("/mobile/redirect/")) ||
                url.host == "mpay.ximalaya.com" && request.method == "GET" &&
                    path.matches(Regex("/mobile/track/pay/v2/[1-9][0-9]*/ts-[0-9]+")) && url.query == "device=android"))
            val builder = request.newBuilder().removeHeader("Cookie").removeHeader("Cookie2")
            val activeLease = lease.takeIf { authenticated }
            activeLease?.let {
                require(it.session.uid > 0 && it.session.token.matches(Regex("[A-Za-z0-9._~+/%=-]{1,4096}")))
            }
            builder.header("Cookie", cookie(activeLease))
                .header("Cookie2", "\$version=1")
                .header("Accept", "*/*")
                .header("User-Agent", userAgent)
            checkOwner()
            // Several metadata/redirect calls form one user operation, not separate
            // full timeout budgets that could leave a tap waiting for minutes.
            if (deadline - System.nanoTime() <= 0) throw java.net.SocketTimeoutException("播放请求超时")
            val timingStage = when {
                path == "/v1/track/baseInfo" -> "metadata"
                path.startsWith("/mobile/redirect/") -> "redirect"
                path.startsWith("/mobile/track/pay/") -> "paid_address"
                path.startsWith("/mobile/others/ca/album/track/") -> "catalogue_fallback"
                path.startsWith("/mobile/v1/album/track/") -> "catalogue"
                path == "/mobile/v1/album/info" -> "album_info"
                else -> null
            }
            timingStage?.let { StartupTiming.mark("${it}_request") }
            val outbound = builder.build()
            val response = try {
                if (path == "/v1/track/baseInfo") HedgedPlaybackRequest.execute(ownedClient, outbound, deadline)
                else ownedClient.newCall(outbound).also { it.timeout().deadlineNanoTime(deadline) }.execute()
            } finally { timingStage?.let { StartupTiming.mark("${it}_response") } }
            try { checkOwner() } catch (failure: Exception) { response.close(); throw failure }
            response
        }
        val api = ContentApi(paidAddress = { track ->
            checkOwner()
            PaidMediaResolver(transport, lease?.session?.uid ?: 0) { file, ep ->
                checkOwner()
                val decoded = paidDecoder(file, ep)
                checkOwner()
                decoded
            }.resolve(track).also { checkOwner() }
        }, transport = transport)
        val result = block(api)
        checkOwner()
        return result
    }
    // The catalogue is public. Account-service failures must not hide episode lists.
    // Playback still requires a fresh account-bound grant in resolve(), never a list hint.
    fun episodeComments(track: Long, page: Int = 1) = operation(authenticated = false) { it.episodeComments(track, page) }
    fun albumDetails(album: Long): Item = operation(authenticated = false) { it.albumDetails(album) }
    fun tracks(album: Long, page: Int = 1): List<Item> = operation(authenticated = false) { it.tracks(album, page) }
    fun locateTrack(album: Long, track: Long): Item = operation(authenticated = false) { it.locateTrack(album, track) }
    fun resolve(track: Item): String = operation { it.resolve(track) }
}
