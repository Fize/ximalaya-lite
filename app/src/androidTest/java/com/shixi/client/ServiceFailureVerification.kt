package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import com.shixi.protocol.ContentAccessException
import com.shixi.protocol.ContentApi
import com.shixi.protocol.ContentServiceException
import com.shixi.protocol.Item
import com.shixi.protocol.ListeningAccess
import com.shixi.protocol.listeningFailureMessage
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Controlled error responses only; fixture credentials never reach the network. */
class ServiceFailureVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(body.toResponseBody()).build()
    override fun onStart() {
        val passed = arrayListOf<String>()
        try {
            check(accountFeedback("刷新登录状态：HTTP 500 secret") == "操作未成功，请稍后重试")
            check(accountFeedback("登录状态校验通过。") == "登录成功")
            check(accountStatusText("已登录（服务端确认）") == "已登录")
            check(syncViewText(CloudState(owner="account:1", message="failure", problem=SyncProblem("进度上传", "失败（HTTP 500）"))) == "同步失败，请稍后重试")
            passed.add("product_copy_hides_technical_details_without_claiming_success")
            val locked = Item(12, "fixture", albumId = 34, access = ListeningAccess.VIP_REQUIRED)
            fun metadata() = JSONObject("""{"ret":0,"trackId":12,"albumId":34,"status":1,"isPublic":true,"isPaid":true,"isAuthorized":true,"vipFreeType":1}""")
            fun assertFailure(code: String, body: String, redirectCode: Int? = null, location: String? = null) {
                val api = ContentApi { request ->
                    if (request.url.encodedPath == "/v1/track/baseInfo") response(request, 200, body)
                    else response(request, redirectCode ?: 200, "private-payload-do-not-show").newBuilder()
                        .apply { location?.let { header("Location", it) } }.build()
                }
                val failure = runCatching { api.resolve(locked) }.exceptionOrNull() ?: error("expected failure: $code")
                check(failure is ContentServiceException)
                val message = listeningFailureMessage(failure)
                check(failure.message.orEmpty().contains(code)); check(message == "内容服务暂时不可用，请稍后重试") { message }
                check(!message.contains("private-payload") && !message.contains("secret"))
                passed.add("vip_diagnosis_$code")
            }
            assertFailure("CONTENT_JSON", "<html>private-payload-do-not-show</html>")
            assertFailure("PLAY_METADATA_MISMATCH", metadata().put("trackId", 99).toString())
            assertFailure("PLAY_URL_MISSING", metadata().toString())
            assertFailure("PLAY_URL_MISSING", metadata().put("playUrl64", JSONObject.NULL).put("playUrl32", JSONObject.NULL).toString())
            for (list in listOf("""[{"url":"secret-ciphertext","version":2}]""", "[{}]", """[{"url":"https://fixture.xmcdn.com/episode.m4a"}]""")) {
                assertFailure("PLAY_URL_LIST_UNSUPPORTED", metadata().put("playUrlList", org.json.JSONArray(list)).toString())
            }
            assertFailure("PLAY_URL_INVALID", metadata().put("playUrl64", "secret-invalid-url").toString())
            assertFailure("PLAY_URL_INSECURE", metadata().put("playUrl64", "http://outside.example/secret").toString())
            val redirect = metadata().put("playUrl64", "https://mobile.ximalaya.com/mobile/redirect/fixture").toString()
            assertFailure("PLAY_REDIRECT_MISSING", redirect, 302)
            assertFailure("PLAY_REDIRECT_HOST", redirect, 302, "https://outside.example/secret")
            assertFailure("PLAY_REDIRECT_HTTP", redirect, 500)
            assertFailure("PLAY_REDIRECT_FORMAT", redirect, 200)
            var retry = false
            val recover = ContentApi { request -> response(request, 200, metadata().apply {
                if (retry) put("playUrl64", "https://fixture.xmcdn.com/episode.m4a")
            }.toString()) }
            check(runCatching { recover.resolve(locked) }.exceptionOrNull() is ContentServiceException)
            retry = true
            check(recover.resolve(locked) == "https://fixture.xmcdn.com/episode.m4a")
            passed.add("vip_diagnosis_retry_recovers_with_fresh_metadata")
            val denied = ContentApi { request -> response(request, 200, metadata().put("isAuthorized", false)
                .put("playUrlList", org.json.JSONArray("""[{"url":"secret-ciphertext"}]""")).toString()) }
            check(runCatching { denied.resolve(locked) }.exceptionOrNull() is ContentAccessException)
            passed.add("vip_denial_remains_authority_failure_before_address_diagnosis")
            for ((failure, code) in listOf(
                java.net.SocketTimeoutException("secret") to "PLAY_TIMEOUT",
                java.net.UnknownHostException("secret") to "PLAY_DNS",
                javax.net.ssl.SSLException("secret") to "PLAY_TLS",
                java.io.IOException("secret") to "PLAY_CONNECTION",
                IllegalStateException("secret") to "PLAY_INTERNAL")) {
                val message = listeningFailureMessage(failure)
                check(!message.contains(code) && !message.contains("secret")); check(message.contains("重试"))
                passed.add("vip_dialog_$code")
            }
            for (ret in listOf(924, 929, 702, 50, 500)) {
                val api = ContentApi { response(it, 200, """{"ret":$ret,"msg":"private-fixture-do-not-show"}""") }
                val failure = try { api.resolve(Item(12, "fixture", true, 34)); error("expected rejection") }
                    catch (error: ContentAccessException) { error }
                check(failure.access == if (ret in listOf(924, 929, 702)) ListeningAccess.UNAVAILABLE else ListeningAccess.UNKNOWN)
                check(!failure.message.orEmpty().contains("private-fixture"))
                check(failure.message.orEmpty().contains(ret.toString()))
                if (ret != 50) check(!failure.message.orEmpty().contains("登录"))
                passed.add("content_business_${ret}_classified_without_false_login_or_raw_message")
            }
            for (ret in listOf(924, 500)) {
                val api = ContentApi { response(it, if (ret == 500) 500 else 200, """{"ret":$ret}""") }
                val error = runCatching { api.tracks(34) }.exceptionOrNull() ?: error("expected failure")
                check(error.message.orEmpty().contains(ret.toString()))
                check(if (ret == 500) error is ContentServiceException else error is ContentAccessException)
                check(!error.message.orEmpty().contains("登录"))
                passed.add("catalogue_${ret}_preserves_specific_service_failure_not_generic_network")
            }
            val lease = AccountLease(1, AccountSession(123, "controlled-fixture-only"), Any())
            for (stage in listOf("history", "subscription")) for (status in listOf(500, 401, 200)) {
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    if (stage == "subscription" && request.url.encodedPath.contains("history/query")) {
                        response(request, 200, """{"ret":0,"data":{"listenModels":[]}}""")
                    } else response(request, status, """{"ret":500,"msg":"private-fixture-do-not-show"}""")
                }.build()
                val api = CloudApi(targetContext, http, current = { true })
                val failure = try { api.read(lease); error("expected rejection") } catch (error: CloudFailure) { error }
                check(failure.message.orEmpty().contains(if (stage == "history") "读取账号历史" else "读取账号订阅"))
                check(failure.retryable == (status == 500))
                check(failure.expired == (status == 401))
                check(!failure.message.orEmpty().contains("private-fixture"))
                check(failure.message.orEmpty().contains(if (status == 200) "业务拒绝（500）" else "${if (status == 500) "HTTP 500" else "授权已失效"}"))
                passed.add("sync_${stage}_${status}_stage_and_existing_retry_identity_contract")
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
            val session = AccountSession(123, "controlled-refresh-fixture")
            for (refreshable in listOf(false, true)) {
                var calls = 0
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    calls++
                    val request = chain.request()
                    check(request.url.host == "passport.ximalaya.com" && request.url.encodedPath == "/user-http-app/v1/token/refresh")
                    check(request.method == "POST" && request.body?.contentType().toString().startsWith("application/json"))
                    check(Buffer().also { request.body!!.writeTo(it) }.readUtf8() == "null")
                    check(request.header("Cookie").orEmpty().contains("1&_token=123&controlled-refresh-fixture;"))
                    response(request, 200, """{"ret":0,"data":{"refreshable":$refreshable,"oldToken":"controlled-refresh-fixture","newToken":"controlled-new-fixture"}}""")
                }.build()
                val result = AccountApi(targetContext, http).refresh(session)
                check(calls == 1)
                check(result.uid == session.uid && result.token == if (refreshable) "controlled-new-fixture" else session.token)
                passed.add("refresh_json_post_${refreshable}_single_request_preserves_identity")
                http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            }
            var refreshFails = true
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.startsWith("/mobile/login/check/")) response(request, 200, """{"ret":0,"login":true}""")
                else {
                    check(request.url.encodedPath == "/user-http-app/v1/token/refresh" && request.method == "POST")
                    response(request, if (refreshFails) 400 else 200, """{"ret":0,"data":{"refreshable":true,"oldToken":"controlled-refresh-fixture","newToken":"controlled-new-fixture"}}""")
                }
            }.build()
            val store = object : SessionStore {
                var saved = session
                override fun read() = saved
                override fun write(session: AccountSession) { saved = session }
                override fun clear() { error("A transient refresh failure must not clear the session") }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager = AccountManager(AccountApi(targetContext, http), store, targetContext.getSharedPreferences("service-failure-fixture", 0), 42, scope)
                fun idle() {
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (manager.state.value.busy && System.nanoTime() < deadline) Thread.sleep(10)
                    check(!manager.state.value.busy)
                }
                idle(); check(manager.state.value.signedIn && store.saved === session)
                passed.add("restored_session_revalidated_with_existing_identity")
                manager.refresh(); idle()
                check(manager.state.value.signedIn && manager.state.value.result.contains("400") && store.saved === session)
                passed.add("refresh_400_preserves_verified_session_and_persisted_token")
                refreshFails = false; manager.refresh(); idle()
                check(manager.state.value.signedIn && store.saved.token == "controlled-new-fixture")
                check(manager.lease()!!.session === store.saved)
                passed.add("explicit_retry_after_400_verifies_and_saves_new_token")
            } finally { scope.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
            finish(-1, Bundle().apply { putStringArrayList("passed", passed); putString("scope", "${passed.size} controlled diagnostics/error/refresh/restore checks; real account VIP response remains unverified") })
        } catch (failure: Throwable) {
            finish(0, Bundle().apply { putStringArrayList("passed", passed); putString("failure", failure.stackTraceToString()) })
        }
    }
}
