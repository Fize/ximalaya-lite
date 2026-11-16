package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.net.Uri
import android.os.Bundle
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.shixi.protocol.ContentAccessException
import com.shixi.protocol.ContentApi
import com.shixi.protocol.Item
import com.shixi.protocol.ListeningAccess
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/** Synthetic verified sessions and intercepted replies, explicitly NOT real SVIP proof. */
class MemberVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun idle(accounts: AccountManager) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (accounts.state.value.busy && System.nanoTime() < until) Thread.sleep(10)
        check(!accounts.state.value.busy)
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null; var failure: Throwable? = null
        runOnMainSync { try { result = block() } catch (caught: Throwable) { failure = caught } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    override fun onStart() {
        val out = Bundle(); val passed = arrayListOf<String>()
        fun test(name: String, body: () -> Unit) { body(); passed.add(name) }
        try {
            val gateway = object : AccountGateway {
                override fun send(mobile: String) { error("SMS forbidden in fixture") }
                override fun login(mobile: String, code: String) = AccountSession(71, "fixture-A")
                override fun check(session: AccountSession) = true
                override fun refresh(session: AccountSession) = AccountSession(71, "fixture-B")
            }
            val store = object : SessionStore {
                private var session: AccountSession? = null
                override fun read() = session
                override fun write(session: AccountSession) { this.session = session }
                override fun clear() { session = null }
            }
            val accounts = AccountManager(gateway, store, targetContext.getSharedPreferences("member-fixture", Context.MODE_PRIVATE), 42)
            idle(accounts)
            fun login() { accounts.login("fixture", "fixture"); idle(accounts); check(accounts.state.value.signedIn) }
            val locked = Item(12, "controlled paid episode", albumId = 34, access = ListeningAccess.VIP_REQUIRED)
            var granted = true; var wrongId = false; var switchOnReply = false; var httpRedirect = false
            val requests = mutableListOf<Request>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); requests.add(request)
                check(request.url.host == "mobile.ximalaya.com")
                val path = request.url.encodedPath
                var code = 200
                val body = when {
                    path == "/v1/track/baseInfo" -> """{"ret":0,"trackId":${if (wrongId) 13 else 12},"albumId":34,"status":1,"isPublic":true,"isPaid":true,"isAuthorized":$granted,"vipFreeType":1,"playUrl64":"https://mobile.ximalaya.com/mobile/redirect/fixture"}"""
                    path.startsWith("/mobile/redirect/") -> { code = 302; "" }
                    path.startsWith("/mobile/v1/album/track/") -> {
                        if (request.header("Cookie") != null) { code = 500; """{"ret":500}""" }
                        else """{"ret":0,"data":{"list":[{"trackId":12,"title":"controlled paid episode","isPaid":true,"isAuthorized":false,"vipFreeType":1}]}}"""
                    }
                    path == "/mobile/v1/album/info" -> """{"ret":0,"albumId":34,"vipFreeType":1}"""
                    else -> error("unexpected endpoint")
                }
                val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (httpRedirect && path == "/v1/track/baseInfo") 302 else code).message("controlled")
                    .body(body.toResponseBody("application/json".toMediaType()))
                if (code == 302 || httpRedirect) response.header("Location", "http://fixture.xmcdn.com/controlled.m4a")
                if (switchOnReply) { switchOnReply = false; accounts.logout() }
                response.build()
            }.build()
            val content = AccountContentApi(targetContext, accounts, http)
            test("controlled_guest_request_has_no_token") {
                granted = false
                check(runCatching { content.resolve(locked) }.exceptionOrNull() is ContentAccessException)
                check(requests.last().header("Cookie") == null)
            }
            login(); granted = true
            test("controlled_paid_grant_first_party_cookie_no_cdn_or_redirect_forwarding") {
                requests.clear()
                check(content.resolve(locked) == "https://fixture.xmcdn.com/controlled.m4a")
                check(requests.size == 2)
                check(requests.all { it.url.host == "mobile.ximalaya.com" && it.header("Cookie").orEmpty().contains("1&_token=71&fixture-A;") })
                check(!http.followRedirects || requests.none { it.url.host.endsWith("xmcdn.com") })
            }
            val uri = Uri.parse("shixi://track/12/34/1")
            test("controlled_signed_in_catalogue_survives_cookie_rejection_without_losing_account") {
                requests.clear()
                check(content.tracks(34).single().id == 12L)
                check(requests.isNotEmpty() && requests.all { it.header("Cookie") == null && it.header("Cookie2") == null })
                check(accounts.state.value.signedIn)
            }
            val resolver = PlaybackResolver(content::tracks, content::resolve, owner = accounts::contentIdentity)
            test("controlled_stale_denied_list_still_queries_authoritative_grant") {
                check(!content.tracks(34).single().playable)
                check(resolver.resolve(uri).host == "fixture.xmcdn.com")
            }
            test("controlled_refresh_rejects_old_lease_and_cached_address") {
                val lease = accounts.lease()!!; val count = requests.size
                accounts.refresh(); idle(accounts)
                check(!accounts.current(lease))
                resolver.resolve(uri)
                check(requests.size > count)
                check(requests.last().header("Cookie").orEmpty().contains("fixture-B"))
            }
            test("controlled_denial_no_fake_vip_and_fresh_retry") {
                resolver.invalidate(null); granted = false
                check((runCatching { resolver.resolve(uri) }.exceptionOrNull() as? ContentAccessException)?.access == ListeningAccess.VIP_REQUIRED)
                granted = true
                check(resolver.resolve(uri).scheme == "https")
            }
            test("controlled_invalid_metadata_ids_rejected") {
                wrongId = true
                check(runCatching { content.resolve(locked) }.isFailure)
                wrongId = false
            }
            test("controlled_metadata_redirect_is_not_followed_with_token") {
                httpRedirect = true; requests.clear()
                check(runCatching { content.resolve(locked) }.isFailure)
                check(requests.size == 1 && requests.single().url.host == "mobile.ximalaya.com")
                httpRedirect = false
            }
            test("controlled_session_switch_mid_response_rejects_grant_next_login_usable") {
                switchOnReply = true
                check(runCatching { content.resolve(locked) }.exceptionOrNull() is ContentAccessException)
                login()
                check(content.resolve(locked).schemeOrNull() == "https")
            }
            test("controlled_owner_switch_mid_resolution_does_not_return_or_cache_url") {
                var owner = 1
                val racing = PlaybackResolver({ _, _ -> listOf(locked) }, { owner = 2; "https://fixture.xmcdn.com/a" }, owner = { owner })
                check(runCatching { racing.resolve(uri) }.exceptionOrNull() is ContentAccessException)
            }
            test("controlled_media_open_read_and_mid_read_switch_are_fenced_no_credentials") {
                var owner = 1; var switch = false; var closed = false; var switchOpen = false
                val upstream = object : DataSource {
                    override fun addTransferListener(listener: TransferListener) {}
                    override fun open(dataSpec: DataSpec): Long { check(dataSpec.httpRequestHeaders["Cookie"] == null); if (switchOpen) owner++; return 4 }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { if (switch) owner++; buffer[offset] = 1; return 1 }
                    override fun getUri() = Uri.parse("https://fixture.xmcdn.com/a")
                    override fun close() { closed = true }
                }
                val media = AccountMediaDataSource(upstream) { owner }
                media.open(DataSpec(Uri.parse("https://fixture.xmcdn.com/a")))
                check(media.read(ByteArray(4), 0, 4) == 1)
                switch = true
                check(runCatching { media.read(ByteArray(4), 0, 4) }.exceptionOrNull() is ContentAccessException)
                media.close(); check(closed)
                switchOpen = true
                check(runCatching { media.open(DataSpec(Uri.parse("https://fixture.xmcdn.com/a"))) }.exceptionOrNull() is ContentAccessException)
                switchOpen = false
                switch = false; media.open(DataSpec(Uri.parse("https://fixture.xmcdn.com/a"))); owner++
                check(runCatching { media.read(ByteArray(4), 0, 4) }.exceptionOrNull() is ContentAccessException)
                media.close()
            }
            test("controlled_logout_invalidates_cached_paid_grant") {
                resolver.resolve(uri); accounts.logout(); granted = false
                check(runCatching { resolver.resolve(uri) }.exceptionOrNull() is ContentAccessException)
            }
            test("public_paid_album_free_prefix_and_ordinary_free_real_endpoint_regression") {
                val public = ContentApi()
                val prefix = public.tracks(13507836).first { it.id == 75598151L }
                check(prefix.free && public.resolve(prefix).startsWith("https://"))
                val ordinary = public.tracks(257813).first { it.id == 2788075L }
                check(ordinary.free && public.resolve(ordinary).startsWith("https://"))
            }
            test("real_service_buffered_free_audio_stops_on_anonymous_identity_change_explicit_next_tap_works") {
                val realAccounts = AccountManager.get(targetContext); idle(realAccounts)
                check(!realAccounts.state.value.signedIn && EncryptedSessionStore(targetContext).read() == null) { "refusing to alter an existing account" }
                val future = main { MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
                val player = future.get(20, TimeUnit.SECONDS)
                fun await(label: String, condition: () -> Boolean) {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                    while (System.nanoTime() < until) { if (condition()) return; Thread.sleep(100) }
                    error(label)
                }
                try {
                    await("storage not ready") { main { player.sessionExtras.getBoolean("storageReady") } }
                    val free = ContentApi().tracks(13507836).first { it.id == 75598151L }
                    main { player.setMediaItem(PlaybackItems.mediaItem(free)); player.prepare(); player.play() }
                    await("free audio not buffered") { main { player.isPlaying && player.currentPosition > 500 && player.bufferedPosition > player.currentPosition + 500 } }
                    realAccounts.logout() // No account or server call: advance anonymous generation only.
                    await("identity change did not stop buffered playback") { main { !player.isPlaying && !player.playWhenReady && player.playbackState == Player.STATE_IDLE && player.sessionExtras.getString("playbackIssue").orEmpty().contains("账号会话已改变") } }
                    Thread.sleep(300)
                    check(main { !player.playWhenReady && player.playbackState == Player.STATE_IDLE })
                    main { player.setMediaItem(PlaybackItems.mediaItem(free)); player.prepare(); player.play() }
                    await("explicit next play failed") { main { player.isPlaying && player.currentPosition > 500 } }
                } finally { main { player.pause(); player.stop(); player.release() } }
            }
            out.putStringArrayList("passed", passed)
            out.putString("scope", "Controlled synthetic authorization and identity tests; public endpoints only. Real SVIP phone acceptance pending.")
            finish(-1, out)
        } catch (failure: Throwable) {
            out.putStringArrayList("passed", passed); out.putString("failure", failure.javaClass.simpleName)
            finish(0, out)
        }
    }
    private fun String.schemeOrNull() = Uri.parse(this).scheme
}
