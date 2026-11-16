package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import com.shixi.protocol.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fixtures never reach a network. Live integration is anonymous and uses the original native decoder. */
class PaidMediaVerification : Instrumentation() {
    private var audioWindowMs = 0L
    override fun onCreate(arguments: Bundle?) { audioWindowMs = arguments?.getString("audioWindowMs")?.toLongOrNull()?.coerceIn(0, 30000) ?: 0; super.onCreate(arguments); start() }
    private fun idle(accounts: AccountManager) { val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6); while (accounts.state.value.busy && System.nanoTime() < end) Thread.sleep(10); check(!accounts.state.value.busy) }
    private fun <T> main(block: () -> T): T { var result: T? = null; runOnMainSync { result = block() }; @Suppress("UNCHECKED_CAST") return result as T }
    override fun onStart() {
        val out = Bundle(); val passed = arrayListOf<String>(); var phase = "start"
        fun test(name: String, body: () -> Unit) { phase = name; body(); passed.add(name) }
        fun reply(request: Request, body: String, code: Int = 200) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
        val track = Item(12, "fixture", albumId = 34, access = ListeningAccess.AUTHORIZED)
        fun candidate() = JSONObject("""{"ret":0,"trackId":12,"albumId":34,"uid":999,"isAuthorized":true,"sampleDuration":0,"domain":"https://fixture.xmcdn.com","fileId":"YWJjZA==","ep":"abcd","apiVersion":"1.0.0","duration":220,"fileFormat":"m4a64"}""")
        var nativeCalls = 0
        val decoded: (String, String) -> Pair<String, String> = { _, _ -> nativeCalls++; "a/b.m4a" to "FM-sign-token-1794350000" }
        try {
            var baseGranted = true; var payload = candidate(); var requestCalls = 0; var fallbackCalls = 0
            fun api() = ContentApi(paidAddress = { item -> fallbackCalls++; PaidMediaResolver({ r -> requestCalls++; reply(r, JSONObject().put("ret", 0).put("fileInfo", org.json.JSONArray().put(payload)).toString()) }, 71, decoded).resolve(item) }) { r -> reply(r, """{"ret":0,"trackId":12,"albumId":34,"isPaid":true,"isAuthorized":$baseGranted,"status":1}""") }
            test("missing_address_dual_grant_resolves_publisher_uid_distinct_from_caller") { val url = api().resolve(track); check(url.contains("uid=71") && url.contains("is_charge=true") && nativeCalls == 1) }
            test("base_denial_never_calls_paid_transport_or_native") { baseGranted = false; val old = fallbackCalls; check(runCatching { api().resolve(track) }.isFailure); check(fallbackCalls == old); baseGranted = true }
            test("free_raw_address_preserved_without_paid_fallback") {
                var called = false
                val free = ContentApi(paidAddress = { called = true; error("unexpected") }) { r -> reply(r, """{"ret":0,"trackId":12,"albumId":34,"isPaid":false,"isHoldCopyright":true,"isSample":false,"playUrl64":"https://fixture.xmcdn.com/free.m4a"}""") }
                check(free.resolve(track.copy(free = true, access = ListeningAccess.FREE)).endsWith("/free.m4a") && !called)
            }
            test("without_callback_missing_address_diagnostic_preserved") {
                val diagnostic = ContentApi { r -> reply(r, """{"ret":0,"trackId":12,"albumId":34,"isPaid":true,"isAuthorized":true}""") }
                check(runCatching { diagnostic.resolve(track) }.exceptionOrNull()?.message.orEmpty().contains("PLAY_URL_MISSING"))
            }
            for (missing in listOf("trackId", "albumId")) test("flat_missing_${missing}_rejected_before_native") {
                val flat = candidate().apply { remove(missing) }; val calls = nativeCalls
                check(runCatching { PaidMediaResolver({ r -> reply(r, flat.toString()) }, 71, decoded).resolve(track) }.isFailure)
                check(nativeCalls == calls)
            }
            val invalid = listOf<(JSONObject) -> Unit>(
                { it.put("ret", 726) }, { it.put("isAuthorized", false) }, { it.remove("isAuthorized") },
                { it.put("trackId", 13) }, { it.put("albumId", 35) }, { it.put("sampleDuration", 1) },
                { it.put("isSample", true) }, { it.put("isTrial", true) }, { it.put("domain", "https://evil.example") },
                { it.put("domain", "https://u@fixture.xmcdn.com") }, { it.put("domain", "https://fixture.xmcdn.com:444") },
                { it.put("domain", "https://fixture.xmcdn.com/path") }, { it.put("apiVersion", "../1") },
                { it.put("fileId", "!!!!") }, { it.put("ep", "\nabc") }, { it.put("duration", -1) })
            invalid.forEachIndexed { i, mutate -> test("unsafe_reply_${i}_rejected_before_native") { payload = candidate().also(mutate); val calls = nativeCalls; check(runCatching { api().resolve(track) }.isFailure); check(nativeCalls == calls) } }
            test("top_level_726_rejected_without_native") { val calls = nativeCalls; check(runCatching { PaidMediaResolver({ r -> reply(r, """{"ret":726}""") }, 0, decoded).resolve(track) }.isFailure); check(nativeCalls == calls) }
            listOf("../a", "/a", "a/%2e%2e/b", "preview/a", "a?b", "a//b").forEachIndexed { i, path -> test("decoded_path_${i}_rejected") { check(runCatching { PaidMediaResolver({ r -> reply(r, candidate().toString()) }, 0) { _, _ -> path to "FM-sign-token-1794350000" }.resolve(track) }.isFailure) } }
            test("malformed_decoded_ep_rejected") { check(runCatching { PaidMediaResolver({ r -> reply(r, candidate().toString()) }, 0) { _, _ -> "a.m4a" to "bad" }.resolve(track) }.isFailure) }
            test("safe_fixed_http_ret_grant_and_decode_diagnostics") {
                fun failure(body: String, code: Int = 200, decoder: (String, String) -> Pair<String, String> = decoded) =
                    runCatching { PaidMediaResolver({ r -> reply(r, body, code) }, 0, decoder).resolve(track) }.exceptionOrNull()!!
                check(failure("private-body-url-token", 503).message.orEmpty().contains("PLAY_PAID_HTTP"))
                check(failure("{\"ret\":726}") is ContentAccessException)
                check(failure(candidate().put("isAuthorized", false).toString()).message.orEmpty().contains("PLAY_PAID_GRANT"))
                check(failure(candidate().toString(), decoder = { _, _ -> error("private-decoded-secret") }).message.orEmpty().contains("PLAY_PAID_DECODE"))
                check(!failure("private-body-url-token", 503).message.orEmpty().contains("private"))
            }
            val gateway = object : AccountGateway {
                 override fun send(mobile: String) { error("forbidden") }
                override fun login(mobile: String, code: String) = AccountSession(71, "fixture-only")
                override fun check(session: AccountSession) = true
                override fun refresh(session: AccountSession) = AccountSession(71, "fixture-next")
            }
            val store = object : SessionStore { var value: AccountSession? = null; override fun read() = value; override fun write(session: AccountSession) { value = session }; override fun clear() { value = null } }
            val accounts = AccountManager(gateway, store, targetContext.getSharedPreferences("paid-fixture", Context.MODE_PRIVATE), 42); idle(accounts)
            fun login() { accounts.login("fixture", "fixture"); idle(accounts); check(accounts.state.value.signedIn) }
            login(); var switchAt = ""; var seenPaid = false
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val r = chain.request(); check(r.url.host in setOf("mobile.ximalaya.com", "mpay.ximalaya.com")); check(r.header("Cookie").orEmpty().contains("fixture-only"))
                val paid = r.url.host == "mpay.ximalaya.com"; if (paid) seenPaid = true
                val body = if (paid) candidate().toString() else """{"ret":0,"trackId":12,"albumId":34,"isPaid":true,"isAuthorized":true,"status":1}"""
                if (switchAt == if (paid) "paid" else "base") accounts.logout()
                reply(r, body)
            }.build()
            val accountDecode: (String, String) -> Pair<String, String> = { file, ep -> if (switchAt == "decode") accounts.logout(); decoded(file, ep) }
            for (where in listOf("base", "paid", "decode")) test("session_switch_${where}_rejects_and_retry_current_session_works") {
                switchAt = where; check(runCatching { AccountContentApi(targetContext, accounts, http, accountDecode).resolve(track) }.exceptionOrNull() is ContentAccessException)
                login(); switchAt = ""
                // Retry uses the current captured session and controlled decoder; no external fixture access.
                seenPaid = false; check(AccountContentApi(targetContext, accounts, http, accountDecode).resolve(track).contains("uid=71")); check(seenPaid)
            }
            test("malformed_ipc_input_rejected_by_private_service_before_live_rebind") {
                check(runCatching { PaidMediaDecoder.decode(targetContext, "!!!!", "abcd", 3000) }.exceptionOrNull() is ContentServiceException)
            }
            test("public_live_forced_missing_base_url_original_native_and_range_audio") {
                val real = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
                var stripped = false; var mpay = false
                val anonymous = real.newBuilder().addInterceptor { chain ->
                    val r = chain.request(); check(r.header("Cookie") == null && r.header("Cookie2") == null)
                    val response = chain.proceed(r)
                    if (r.url.encodedPath == "/v1/track/baseInfo") {
                        response.use { val json = JSONObject(it.body.string()); check(json.get("isPaid") == true && json.get("isAuthorized") == true); json.remove("playUrl64"); json.remove("playUrl32"); stripped = true; it.newBuilder().body(json.toString().toResponseBody()).build() }
                    } else { check(r.url.host == "mpay.ximalaya.com"); mpay = true; response }
                }.build()
                val guest = AccountManager(gateway, object : SessionStore { override fun read(): AccountSession? = null; override fun write(session: AccountSession) { error("guest") }; override fun clear() {} }, targetContext.getSharedPreferences("paid-guest", Context.MODE_PRIVATE), 42); idle(guest)
                val url = AccountContentApi(targetContext, guest, anonymous).resolve(Item(570921539, "public", albumId = 70003682, access = ListeningAccess.FREE))
                check(stripped && mpay && url.contains("uid=0"))
                real.newCall(Request.Builder().url(url).header("Range", "bytes=0-4095").build()).execute().use { r -> check(r.code == 206 && r.body.bytes().size == 4096); out.putInt("public_audio_http", r.code) }
                val player = main { androidx.media3.exoplayer.ExoPlayer.Builder(targetContext).build().apply { setMediaItem(androidx.media3.common.MediaItem.fromUri(url)); prepare(); play() } }
                fun await(checker: () -> Boolean) { val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40); while (System.nanoTime() < end) { if (main(checker)) return; Thread.sleep(100) }; error("audio timeout") }
                try {
                    await { player.isPlaying && player.currentPosition > 500 }
                    sendStatus(1, Bundle().apply { putString("phase", "decoded_media_playing") }); if (audioWindowMs > 0) Thread.sleep(audioWindowMs)
                    main { player.pause() }; check(main { !player.playWhenReady }); main { player.seekTo(60000); player.play() }
                    await { player.isPlaying && player.currentPosition > 60500 }
                    out.putBoolean("native_audio_pause_seek_resume", true)
                } finally { main { player.release() } }
            }
            out.putStringArrayList("passed", passed); out.putString("scope", "Real anonymous public native audio; authenticated SVIP PHONE PENDING"); finish(-1, out)
        } catch (failure: Throwable) { out.putString("phase", phase); out.putStringArrayList("passed", passed); out.putString("failure", failure.javaClass.simpleName); finish(0, out) }
    }
}
