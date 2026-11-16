package com.shixi.client

import android.content.Context
import android.os.Build
import com.ximalaya.ting.android.loginservice.LoginEncryptUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

// Credentials deliberately have no generated toString(), copy() or diagnostic representation.
class AccountSession(val uid: Long, val token: String)
class AccountFailure(val step: String, val reason: String) : Exception("$step：$reason")
interface AccountGateway {
    fun send(mobile: String)
    fun login(mobile: String, code: String): AccountSession
    fun check(session: AccountSession): Boolean
    fun refresh(session: AccountSession): AccountSession = session
    fun logout(session: AccountSession) {}
}

class AccountApi(private val context: Context, private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()) : AccountGateway {
    private val device = context.getSharedPreferences("account-device", Context.MODE_PRIVATE).let {
        it.getString("id", null) ?: UUID.randomUUID().toString().also { id -> it.edit().putString("id", id).commit() }
    }
    private val crypto by lazy { LoginEncryptUtil() }

    private fun request(step: String, path: String, fields: Map<String, String>? = null, session: AccountSession? = null, postWithoutFields: Boolean = false): JSONObject {
        val cookie = buildString {
            append("1&_device=android&$device&9.4.74.3;impl=${context.packageName};osversion=${Build.VERSION.SDK_INT};")
            if (session != null) append("1&_token=${session.uid}&${session.token};")
        }
        val builder = Request.Builder().url("https://passport.ximalaya.com/$path")
            .header("User-Agent", "ting_9.4.74.3(Android${Build.VERSION.SDK_INT})")
            .header("Accept", "*/*").header("Cookie2", "\$version=1").header("Cookie", cookie)
        if (fields != null || postWithoutFields) builder.post((if (fields == null) "null" else JSONObject(fields).toString()).toRequestBody("application/json; charset=utf-8".toMediaType()))
        try {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw AccountFailure(step, "HTTP ${response.code}")
                val data = try { JSONObject(response.body.string()) } catch (_: Exception) { throw AccountFailure(step, "响应格式不符") }
                if (!data.has("ret")) throw AccountFailure(step, "缺少服务端结果")
                val ret = data.optInt("ret", -1)
                if (ret != 0) {
                    val reason = if (ret in setOf(20000, 20004, 20005, 20012, 33009)) "需要官方额外验证（$ret）" else "服务端拒绝（$ret）"
                    // Server messages may echo phone numbers or credentials: never display/log them.
                    throw AccountFailure(step, reason)
                }
                return data
            }
        } catch (failure: AccountFailure) { throw failure }
        catch (_: IOException) { throw AccountFailure(step, "网络不可用或请求超时，请手动重试") }
    }

    private fun nonce(): String = request("连接登录服务", "mobile/nonce/${System.currentTimeMillis()}")
        .optString("nonce").takeIf { it.isNotBlank() && it != "null" }
        ?: throw AccountFailure("连接登录服务", "缺少临时凭据")

    private fun signed(step: String, fields: Map<String, String>): Map<String, String> = try {
        val all = fields + ("nonce" to nonce())
        val signature = crypto.sign(context, all)
        if (signature.isNullOrBlank()) throw AccountFailure(step, "无法生成请求签名")
        all + ("signature" to signature)
    } catch (failure: AccountFailure) { throw failure }
    catch (_: LinkageError) { throw AccountFailure(step, "此设备不支持账号组件（需 ARM64）") }
    catch (_: Exception) { throw AccountFailure(step, "账号组件未能完成请求准备") }

    private fun mobile(value: String): String = try {
        require(value.matches(Regex("1[3-9][0-9]{9}")))
        crypto.encrypt(value).takeIf { !it.isNullOrBlank() } ?: throw IllegalStateException()
    } catch (_: LinkageError) { throw AccountFailure("准备手机号", "此设备不支持账号组件（需 ARM64）") }
    catch (_: Exception) { throw AccountFailure("准备手机号", "请检查中国大陆手机号或设备兼容性") }

    override fun send(mobile: String) {
        request("发送验证码", "mobile/sms/v3/send", signed("发送验证码", mapOf("mobile" to mobile(mobile), "sendType" to "1", "biz" to "1")))
    }
    override fun login(mobile: String, code: String): AccountSession {
        if (!code.matches(Regex("[0-9]{4,8}"))) throw AccountFailure("校验验证码", "请输入4至8位短信验证码")
        val verify = request("校验验证码", "mobile/sms/v2/verify", signed("校验验证码", mapOf("mobile" to mobile(mobile), "code" to code)))
        val key = verify.optString("bizKey").takeIf { it.isNotBlank() && it != "null" }
            ?: throw AccountFailure("校验验证码", "缺少短信校验凭据")
        val data = request("账号登录", "mobile/login/quick/v2", signed("账号登录", mapOf("mobile" to mobile(mobile), "smsKey" to key)))
        val uid = data.optLong("uid")
        val token = data.optString("token")
        if (uid <= 0 || !token.matches(Regex("[A-Za-z0-9._~+/%=-]{1,4096}"))) throw AccountFailure("账号登录", "账号凭据格式不符")
        return AccountSession(uid, token)
    }
    override fun refresh(session: AccountSession): AccountSession {
        // LoginRequest.c uses the JSON POST bridge, even though it has no business fields.
        val data = request("刷新登录状态", "user-http-app/v1/token/refresh", session = session, postWithoutFields = true).getJSONObject("data")
        if (!data.getBoolean("refreshable")) return session
        if (data.getString("oldToken") != session.token) throw AccountFailure("刷新登录状态", "旧凭据不匹配，未替换当前会话")
        val token = data.getString("newToken")
        if (!token.matches(Regex("[A-Za-z0-9._~+/%=-]{1,4096}"))) throw AccountFailure("刷新登录状态", "账号凭据格式不符")
        return AccountSession(session.uid, token)
    }
    override fun logout(session: AccountSession) { request("退出账号", "mobile/logout", fields = mapOf("ts" to System.currentTimeMillis().toString()), session = session) }
    override fun check(session: AccountSession): Boolean {
        val data = request("校验登录状态", "mobile/login/check/${System.currentTimeMillis()}", session = session)
        if (data.opt("login") !is Boolean) throw AccountFailure("校验登录状态", "缺少明确的登录状态")
        return data.getBoolean("login")
    }
}
