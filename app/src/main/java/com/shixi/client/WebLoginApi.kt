package com.shixi.client

import android.content.Context
import android.os.Build
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A validated challenge, never a general purpose authenticated URL. */
internal class WebLoginCode private constructor(private val id: String) {
    fun url(): HttpUrl = checkNotNull("https://mobile.ximalaya.com/passport-sign-mobile/qrcode/login/$id/qrcode-7172636f6465".toHttpUrlOrNull())
        .newBuilder().addQueryParameter("to", "喜马拉雅WEB").build()

    companion object {
        private val path = Regex("/passport-sign-mobile/qrcode/login/([A-Fa-f0-9]{32})/qrcode-7172636f6465")
        fun parse(raw: String): WebLoginCode? {
            if (raw.length !in 1..2048 || raw.any { it.isWhitespace() || it == '\\' || it.code < 32 }) return null
            val url = raw.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.host != "mobile.ximalaya.com" || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
                url.querySize != 1 || url.queryParameterNames != setOf("to") || url.queryParameter("to") != "喜马拉雅WEB") return null
            val match = path.matchEntire(url.encodedPath) ?: return null
            return WebLoginCode(match.groupValues[1])
        }
    }
}

internal class WebLoginFailure(val notice: String) : IOException()

internal class WebLoginApi(context: Context, private val account: AccountManager, client: OkHttpClient = OkHttpClient()) {
    private val device = context.getSharedPreferences("account-device", Context.MODE_PRIVATE).let {
        it.getString("id", null) ?: UUID.randomUUID().toString().also { id -> it.edit().putString("id", id).commit() }
    }
    private val packageName = context.packageName
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun call(code: WebLoginCode, lease: AccountLease, permitted: () -> Boolean): Call {
        val url = code.url()
        check(WebLoginCode.parse(url.toString()) != null)
        val request = Request.Builder().url(url)
            .header("User-Agent", "ting_9.4.74.3(Android${Build.VERSION.SDK_INT})")
            .header("Accept", "*/*").header("Cookie2", "\$version=1")
            .header("Cookie", "1&_device=android&$device&9.4.74.3;impl=$packageName;osversion=${Build.VERSION.SDK_INT};1&_token=${lease.session.uid}&${lease.session.token};")
            .post(object : RequestBody() {
                override fun contentType() = "application/json; charset=utf-8".toMediaType()
                override fun contentLength() = 4L
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.writeUtf8("null") }
            }).build()
        // Recheck just before dispatch; leaving the screen or changing accounts revokes the permit.
        val guarded = http.newBuilder().apply {
            interceptors().add(0) { chain ->
                if (!account.current(lease) || !permitted()) throw WebLoginFailure("登录状态已变化，请重新扫描")
                chain.proceed(chain.request())
            }
        }.build()
        return guarded.newCall(request)
    }

    fun accept(response: Response) {
        response.use {
            if (it.code == 401 || it.code == 403) throw WebLoginFailure("登录状态未通过验证，请更新登录状态后重新扫描")
            if (!it.isSuccessful) throw WebLoginFailure("未能确认登录，请刷新网页二维码后重新扫描")
            val source = it.body.source()
            if (source.request(65_537)) throw WebLoginFailure("未能确认登录，请刷新网页二维码后重新扫描")
            val text = source.readUtf8()
            val data = try { JSONObject(text) } catch (_: Exception) { throw WebLoginFailure("未能确认登录，请刷新网页二维码后重新扫描") }
            val result = data.opt("ret")
            if (result is Number && result.toLong() == 50L) throw WebLoginFailure("登录状态未通过验证，请更新登录状态后重新扫描")
            if ((result !is Int && result !is Long) || (result as Number).toLong() != 0L)
                throw WebLoginFailure("二维码未获授权或已失效，请刷新网页二维码后重新扫描")
        }
    }
}
