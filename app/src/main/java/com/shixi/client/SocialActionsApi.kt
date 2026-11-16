package com.shixi.client

import android.content.Context
import android.os.Build
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Each write is tied to the account that initiated it and is never automatically retried. */
internal class SocialActionsApi(context: Context, private val accounts: AccountManager = AccountManager.get(context),
    client: OkHttpClient = OkHttpClient()) {
    private val app = context.applicationContext
    private val device = app.getSharedPreferences("account-device", Context.MODE_PRIVATE).getString("id", "").orEmpty()
    private val http = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun lease(): AccountLease? = accounts.lease()
    private fun send(lease: AccountLease, path: String, fields: (AccountLease) -> Map<String, String>): JSONObject {
        check(accounts.current(lease))
        val values = fields(lease)
        val form = FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()
        val cookie = "1&_device=android&$device&9.4.74.3;impl=${app.packageName};osversion=${Build.VERSION.SDK_INT};1&_token=${lease.session.uid}&${lease.session.token};"
        val request = Request.Builder().url("https://mobile.ximalaya.com/$path").header("Cookie", cookie)
            .header("Cookie2", "\$version=1").header("User-Agent", "ting_9.4.74.3(Android${Build.VERSION.SDK_INT})").post(form).build()
        if (!accounts.current(lease)) throw IOException("账号状态已变化")
        val owned = http.newBuilder().addNetworkInterceptor { chain ->
            if (!accounts.current(lease)) throw IOException("账号状态已变化")
            chain.proceed(chain.request())
        }.build()
        owned.newCall(request).execute().use { response ->
            if (!accounts.current(lease)) throw IOException("账号状态已变化")
            if (!response.isSuccessful) throw IOException("操作未成功，请重试")
            val result = try { JSONObject(response.body.string()) } catch (_: Exception) { throw IOException("操作结果暂不可确认") }
            if (!result.has("ret") || result.optInt("ret", -1) != 0) throw IOException("操作未成功，请重试")
            return result
        }
    }
    fun likeStatus(lease: AccountLease, trackId: Long): Boolean {
        require(trackId > 0)
        val cookie = "1&_device=android&$device&9.4.74.3;impl=${app.packageName};osversion=${Build.VERSION.SDK_INT};1&_token=${lease.session.uid}&${lease.session.token};"
        val req = Request.Builder().url("https://mobile.ximalaya.com/mobile/track/relation?trackIds=$trackId&device=android")
            .header("Cookie", cookie).header("Cookie2", "\$version=1").header("User-Agent", "ting_9.4.74.3(Android${Build.VERSION.SDK_INT})").build()
        if (!accounts.current(lease)) throw IOException("账号状态已变化")
        val owned = http.newBuilder().addNetworkInterceptor { chain ->
            if (!accounts.current(lease)) throw IOException("账号状态已变化")
            chain.proceed(chain.request())
        }.build()
        owned.newCall(req).execute().use { res ->
            if (!accounts.current(lease) || !res.isSuccessful) throw IOException("点赞状态暂不可用")
            val json = JSONObject(res.body.string())
            if (json.optInt("ret", -1) != 0) throw IOException("点赞状态暂不可用")
            val list = json.optJSONArray("data") ?: try { JSONArray(json.optString("data")) } catch (_: Exception) { throw IOException("点赞状态暂不可用") }
            for (index in 0 until list.length()) {
                val row = list.optJSONObject(index) ?: continue
                if (row.optLong("trackId") == trackId && row.has("isLike")) return row.getBoolean("isLike")
            }
            throw IOException("点赞状态暂不可用")
        }
    }
    fun comment(lease: AccountLease, trackId: Long, text: String) {
        require(trackId > 0 && text.isNotBlank() && text.length <= 500)
        send(lease, "comment-mobile/v1/create") { lease -> mapOf("trackId" to trackId.toString(), "content" to text, "uid" to lease.session.uid.toString(), "token" to lease.session.token, "synchaos" to "0") }
    }
    fun like(lease: AccountLease, trackId: Long, liked: Boolean) {
        require(trackId > 0)
        send(lease, "favourite-business/favorite/track") { mapOf("trackId" to trackId.toString(), "favorite" to liked.toString()) }
    }
}
