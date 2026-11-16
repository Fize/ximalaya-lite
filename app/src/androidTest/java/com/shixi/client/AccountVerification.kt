package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import com.ximalaya.ting.android.loginservice.LoginEncryptUtil
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/** No real phone, OTP or SMS-send request is used; only nonce and synthetic number validation. */
class AccountVerification : Instrumentation() {
    private var syntheticOnly=false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); syntheticOnly=arguments?.getString("phase")=="synthetic"; start() }
    private class MemoryStore : SessionStore {
        @Volatile var value: AccountSession? = null
        override fun read() = value
        override fun write(session: AccountSession) { value = session }
        override fun clear() { value = null }
    }
    private open class Gateway : AccountGateway {
        @Volatile var valid = true
        @Volatile var failCheck = false
        @Volatile var sends = 0
        override fun send(mobile: String) { sends++; throw AccountFailure("发送验证码", "模拟超时") }
        override fun login(mobile: String, code: String) = AccountSession(100, "synthetic-session-fixture")
        override fun check(session: AccountSession): Boolean {
            if (failCheck) throw AccountFailure("校验登录状态", "模拟断网")
            return valid
        }
    }
    private fun idle(manager: AccountManager) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (manager.state.value.busy && System.nanoTime() < end) Thread.sleep(10)
        check(!manager.state.value.busy) { "operation did not finish" }
    }
    override fun onStart() {
        val results = Bundle()
        val prefs = targetContext.getSharedPreferences("account-verification-only", Context.MODE_PRIVATE)
        val completed = arrayListOf<String>()
        fun test(name: String, body: () -> Unit) { body(); completed.add(name) }
        fun manager(api: AccountGateway, store: SessionStore) = AccountManager(api, store, prefs, 42).also { idle(it) }
        try {
            test("keystore_encryption_roundtrip_and_tamper") {
                val store = EncryptedSessionStore(targetContext)
                // The isolated test emulator must be logged out before running this test.
                check(store.read() == null) { "refusing to overwrite an existing session" }
                store.write(AccountSession(100, "synthetic-session-fixture"))
                val file = File(targetContext.noBackupFilesDir, "account-session-v1")
                val raw = file.readBytes()
                check(!String(raw).contains("synthetic-session-fixture"))
                check(store.read()?.uid == 100L && store.read()?.token == "synthetic-session-fixture")
                file.writeText("corrupt-test-envelope")
                check(runCatching { store.read() }.isFailure)
                store.clear(); check(store.read() == null)
            }
            test("login_false_is_not_saved") {
                val api = Gateway().apply { valid = false }; val store = MemoryStore(); val m = manager(api, store)
                m.login("synthetic", "synthetic"); idle(m)
                check(!m.state.value.signedIn && store.value == null)
            }
            test("verified_login_restore_and_logout") {
                val api = Gateway(); val store = MemoryStore(); val m = manager(api, store)
                m.login("synthetic", "synthetic"); idle(m); check(m.state.value.signedIn && store.value != null)
                val restored = manager(api, store); check(restored.state.value.signedIn)
                restored.logout(); check(!restored.state.value.signedIn && store.value == null)
            }
            test("failed_login_check_then_retry") {
                val api = Gateway().apply { failCheck = true }; val store = MemoryStore(); val m = manager(api, store)
                m.login("synthetic", "synthetic"); idle(m); check(!m.state.value.signedIn && store.value == null)
                api.failCheck = false; m.login("synthetic", "synthetic"); idle(m); check(m.state.value.signedIn)
                api.failCheck = true; m.check(); idle(m); check(m.state.value.result.contains("模拟断网"))
                api.failCheck = false; m.check(); idle(m); check(m.state.value.signedIn)
                api.valid = false; m.check(); idle(m); check(!m.state.value.signedIn && store.value == null)
            }
            test("logout_rejects_late_login_and_allows_next") {
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val api = object : Gateway() {
                    @Volatile var first = true
                    override fun login(mobile: String, code: String): AccountSession {
                        if (first) { first = false; entered.countDown(); check(release.await(6, TimeUnit.SECONDS)) }
                        return super.login(mobile, code)
                    }
                }
                val store = MemoryStore(); val m = manager(api, store)
                m.login("synthetic", "synthetic"); check(entered.await(3, TimeUnit.SECONDS)); m.logout()
                release.countDown(); Thread.sleep(100); check(!m.state.value.signedIn && store.value == null)
                m.login("synthetic", "synthetic"); idle(m); check(m.state.value.signedIn)
            }
            test("sms_timeout_no_retry_and_persisted_cooldown") {
                prefs.edit().clear().commit()
                val api = Gateway(); val m = manager(api, MemoryStore())
                m.send("13800000000"); idle(m); check(api.sends == 1 && m.remainingSeconds() in 1..60)
                m.send("13800000000"); idle(m); check(api.sends == 1)
                val restored = manager(api, MemoryStore()); check(restored.remainingSeconds() in 1..60)
            }
            test("logout_cancels_queued_sms_before_new_send") {
                prefs.edit().clear().commit()
                val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
                }
                fun drain() { while (true) (tasks.poll() ?: break).run() }
                val api = Gateway()
                val m = AccountManager(api, MemoryStore(), prefs, 42, CoroutineScope(SupervisorJob() + dispatcher))
                drain(); m.send("13800000000"); m.logout(); m.send("13800000000"); drain()
                check(api.sends == 1 && !m.state.value.busy && m.remainingSeconds() > 0)
            }
            if(syntheticOnly) { results.putString("result","PASS"); results.putStringArrayList("tests",completed); finish(-1,results); return }
            test("native_signing_own_package") {
                val crypto = LoginEncryptUtil(); val fields = mapOf("nonce" to "synthetic", "mobile" to "offline-test")
                val one = crypto.sign(targetContext, fields)
                check(one.length == 40 && one == crypto.sign(targetContext, fields))
                check(crypto.encrypt("offline-test").isNotBlank())
            }
            test("public_nonce_and_signing_no_sms") { AccountApi::class.java.getDeclaredMethod("signed", String::class.java, Map::class.java).apply { isAccessible = true }.invoke(AccountApi(targetContext), "测试签名", mapOf("probe" to "test-only")) }
            test("synthetic_verification_uses_correct_product") {
                val crypto = LoginEncryptUtil()
                fun request(path: String, fields: Map<String, String>? = null): org.json.JSONObject {
                    val connection = java.net.URL("https://passport.ximalaya.com/$path").openConnection() as javax.net.ssl.HttpsURLConnection
                    connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "ting_9.4.74.3(Android37)")
                    connection.setRequestProperty("Cookie", "1&_device=android&offline-diagnostic-fixture&9.4.74.3;impl=com.shixi.client;osversion=37;")
                    if (fields != null) {
                        connection.requestMethod = "POST"; connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(org.json.JSONObject(fields).toString().toByteArray()) }
                    }
                    return try { connection.inputStream.bufferedReader().use { org.json.JSONObject(it.readText()) } }
                    finally { connection.disconnect() }
                }
                val nonce = request("mobile/nonce/${System.currentTimeMillis()}").getString("nonce")
                val fields = mapOf("mobile" to crypto.encrypt("offline-not-a-phone"), "code" to "000000", "nonce" to nonce)
                // No SMS request, valid phone number or real OTP is used. Correct signing should
                // reach the number validator instead of the observed generic 10001 rejection.
                val reply = request("mobile/sms/v2/verify", fields + ("signature" to crypto.sign(targetContext, fields)))
                check(reply.optInt("ret") == 10002) { "synthetic number check returned ${reply.optInt("ret")}" }
                check(targetContext.packageName == "com.shixi.client")
            }
            results.putStringArrayList("passed", completed); results.putInt("count", completed.size)
            results.putString("scope", "synthetic account state; Keystore; JNI; public nonce and non-phone verification; no SMS send/account")
            finish(android.app.Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            results.putStringArrayList("passed", completed)
            results.putString("failure", failure.javaClass.simpleName + ":" + failure.message)
            finish(android.app.Activity.RESULT_CANCELED, results)
        } finally { prefs.edit().clear().commit() }
    }
}
