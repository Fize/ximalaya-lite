package com.shixi.client

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CloudSnapshot(val history: List<CloudHistoryRow>, val subscriptions: List<CloudSubscriptionRow>)
class CloudFailure(val expired: Boolean = false, message: String, val retryable: Boolean = false) : IOException(message)
interface CloudGateway {
    fun read(lease: AccountLease): CloudSnapshot
    fun subscription(lease: AccountLease, row: CloudSubscriptionRow)
    fun progress(lease: AccountLease, row: CloudHistoryRow)
}

/** Business identity is never installed on image/CDN clients; redirects are disabled. */
class CloudApi internal constructor(private val context: Context, private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build(),
    private val current: (AccountLease) -> Boolean = { AccountManager.get(context).current(it) },
    private val sign: (Map<String, String>) -> String = { HistorySigner.sign(context, it) }) : CloudGateway {
    private val historyChannel = "default"
    private val device = context.getSharedPreferences("account-device", Context.MODE_PRIVATE).let {
        it.getString("id", null) ?: UUID.randomUUID().toString().also { id -> check(it.edit().putString("id", id).commit()) }
    }
    private fun request(lease: AccountLease, path: String, fields: Map<String, String>, post: Boolean = false, historyWrite: Boolean = false): JSONObject {
        val stage = when (path) {
            "nyx/v1/history/query/android" -> "读取账号历史"
            "subscribe/v3/subscribe/list" -> "读取账号订阅"
            "nyx/v1/history/merge/android" -> "同步收听进度"
            else -> "同步账号订阅"
        }
        val host = if (historyWrite) "xdcs-collector.ximalaya.com" else "mobile.ximalaya.com"
        val url = "https://$host/$path".toHttpUrl().newBuilder()
        if (!post) fields.forEach { (k, v) -> url.addQueryParameter(k, v) }
        val token = lease.session.token
        require(lease.session.uid > 0 && token.matches(Regex("[A-Za-z0-9._~+/%=-]{1,4096}")))
        val channelCookie = if (historyWrite) "channel=$historyChannel;" else ""
        val cookie = "1&_device=android&$device&9.4.74.3;${channelCookie}impl=${context.packageName};osversion=${Build.VERSION.SDK_INT};1&_token=${lease.session.uid}&$token;"
        val builder = Request.Builder().url(url.build()).header("Cookie", cookie).header("Cookie2", "\$version=1")
            .header("User-Agent", "ting_9.4.74.3(Android${Build.VERSION.SDK_INT})").header("Accept", "*/*")
        if (post) builder.post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
        val call = http.newCall(builder.build())
        // Signing and each preceding page may have outlived logout/account switching.
        // Recheck at the common send boundary, including every write and read page.
        if (!current(lease)) throw CloudFailure(message = "账号会话已改变，已停止同步请求，原账号待同步记录保留")
        call.execute().use { response ->
            if (response.code in listOf(401, 403)) throw CloudFailure(true, "$stage：账号授权已失效，请重新登录")
            if (!response.isSuccessful) throw CloudFailure(message = "$stage：同步服务 HTTP ${response.code}，尚未确认成功，原记录保留", retryable = response.code in 500..599 || response.code == 408 || response.code == 429)
            val data = try { JSONObject(response.body.string()) } catch (_: Exception) { throw CloudFailure(message = "同步响应格式不符") }
            if (!data.has("ret")) throw CloudFailure(message = "缺少同步结果，尚未确认成功")
            if (data.optInt("ret", -1) != 0) throw CloudFailure(message = "$stage：同步服务业务拒绝（${data.optInt("ret", -1)}），原记录保留，尚未确认成功")
            return data
        }
    }
    override fun read(lease: AccountLease): CloudSnapshot {
        val owner = lease.owner; val history = mutableListOf<CloudHistoryRow>(); val subscriptions = mutableListOf<CloudSubscriptionRow>()
        var stage = "历史读取"
        try {
        var page = 1
        while (true) {
            val result = request(lease, "nyx/v1/history/query/android", mapOf("uid" to lease.session.uid.toString(), "pageId" to page.toString(), "pageSize" to "20", "timeStamp" to System.currentTimeMillis().toString()))
            // The original client reads data via optString + Gson: both object and
            // JSON-encoded object are valid. Keep the list mandatory, so malformed
            // responses cannot become an authoritative empty snapshot.
            val value = result.get("data")
            val data = if (value is String) JSONObject(value) else result.getJSONObject("data")
            val list = data.getJSONArray("listenModels")
            for (i in 0 until list.length()) {
                val row = list.getJSONObject(i)
                if (row.optInt("type") != 1 || row.optLong("deleteTime") > 0) continue
                val id = row.getLong("childId"); val album = row.getLong("itemId"); val at = row.getLong("endedAt")
                require(id > 0 && album > 0 && at >= 0)
                // CloudHistroyListenModel uses a primitive int: absent/null means 0.
                // An explicitly malformed value must still fail the complete read.
                val seconds = if (!row.has("breakSecond") || row.isNull("breakSecond")) 0L else row.getLong("breakSecond")
                val position = seconds.coerceAtLeast(0) * 1000
                val duration = row.optLong("length").coerceAtLeast(0) * 1000
                history.add(CloudHistoryRow(owner, id, album, row.optString("childTitle").take(1000), safeCover(row.optString("itemCoverUrl")), position, duration, duration > 0 && position >= duration, 0, at))
            }
            if (list.length() < 20) break
            if (++page > 100) throw CloudFailure(message = "历史超过本次同步范围，未覆盖本机数据，请稍后重试")
        }
        stage = "订阅读取"
        page = 1
        while (true) {
            val result = request(lease, "subscribe/v3/subscribe/list", mapOf("pageId" to page.toString(), "pageSize" to "20", "device" to "android"))
            val value = result.getJSONObject("data").get("subscribeAlbumResult")
            val albums = if (value is JSONObject) value else JSONObject(value.toString())
            val list = albums.getJSONArray("list")
            for (i in 0 until list.length()) {
                val row = list.getJSONObject(i); val id = row.optLong("albumId", row.optLong("id"))
                require(id > 0)
                subscriptions.add(CloudSubscriptionRow(owner, id, row.optString("title").ifBlank { row.optString("albumTitle") }.take(1000), safeCover(row.optString("coverMiddle").ifBlank { row.optString("coverPath") }), true, true))
            }
            if (list.length() < 20) break
            if (++page > 100) throw CloudFailure(message = "订阅超过本次同步范围，未覆盖本机数据，请稍后重试")
        }
        return CloudSnapshot(history.distinctBy { it.id }, subscriptions.distinctBy { it.id })
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: CloudFailure) { throw error }
        catch (error: Exception) { throw CloudReadFailure(stage, error) }
    }
    override fun subscription(lease: AccountLease, row: CloudSubscriptionRow) {
        require(row.owner == lease.owner && row.id > 0)
        request(lease, "subscribe/album/subscribe/" + if (row.subscribed) "create" else "delete",
            mapOf("albumId" to row.id.toString(), "bizType" to "-1", "autoFollow" to "0"), post = true)
    }
    override fun progress(lease: AccountLease, row: CloudHistoryRow) {
        require(row.owner == lease.owner && row.id > 0 && row.albumId > 0 && row.localAt > 0)
        val record = JSONObject().put("endedAt", row.localAt.toString()).put("startedAt", (row.localAt - 1).toString())
            .put("type", "1").put("itemId", row.albumId.toString()).put("childId", row.id.toString())
            .put("breakSecond", (row.positionMs / 1000).toString()).put("platform", "0").put("bizSubType", "0")
            .put("trackType", "0").put("decoupleStatus", "0").put("direction", "0")
        val fields = mapOf("mergeRecords" to JSONArray().put(record).toString())
        // ap.a(map) signs identity-augmented copy; only original fields+signature go on wire.
        val signFields = fields + mapOf("uid" to lease.session.uid.toString(), "token" to lease.session.token,
            "device" to "android", "deviceId" to device, "version" to "9.4.74.3", "channel" to historyChannel, "impl" to context.packageName)
        val signature = sign(signFields)
        if (signature.isNullOrBlank()) throw CloudFailure(message = "历史签名未生成，记录仍保留")
        request(lease, "nyx/v1/history/merge/android", fields + ("signature" to signature), post = true, historyWrite = true)
    }
    private fun safeCover(value: String): String = value.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }?.take(4096) ?: ""
}
