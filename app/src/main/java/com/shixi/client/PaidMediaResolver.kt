package com.shixi.client

import com.shixi.protocol.ContentServiceException
import com.shixi.protocol.Item
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Called only after ContentApi's fresh exact-episode paid grant, within the same account lease. */
internal class PaidMediaResolver(private val transport: (Request) -> Response, private val uid: Long,
    private val decode: (String, String) -> Pair<String, String>) {
    fun resolve(track: Item): String {
        var stage = "PAYLOAD"
        try {
            require(track.id > 0 && track.albumId > 0 && uid >= 0)
            val request = Request.Builder().url("https://mpay.ximalaya.com/mobile/track/pay/v2/${track.id}/ts-${System.currentTimeMillis()}?device=android")
                .header("User-Agent", "ting_9.4.74.3(Android)").get().build()
            val json = transport(request).use { response ->
                if (!response.isSuccessful) throw ContentServiceException("获取已授权节目地址：内容服务 HTTP ${response.code}（PLAY_PAID_HTTP）")
                val source = response.body.source()
                source.request(262_145)
                require(source.buffer.size <= 262_144)
                JSONObject(source.readUtf8())
            }
            fun checkRet(o: JSONObject, mandatory: Boolean = false) {
                require(!mandatory || o.has("ret"))
                if (o.has("ret")) {
                    val ret = o.getInt("ret")
                    if (ret == 726) throw com.shixi.protocol.ContentAccessException(com.shixi.protocol.ListeningAccess.PAID_REQUIRED, "服务端未确认本集收听权益（PLAY_PAID_DENIED_726）")
                    if (ret != 0) throw ContentServiceException("获取已授权节目地址：内容服务拒绝请求（PLAY_PAID_RET_$ret）")
                }
            }
            checkRet(json, true)
            fun validate(o: JSONObject, idsRequired: Boolean) {
                checkRet(o)
                if (idsRequired || o.has("trackId")) require(o.getLong("trackId") == track.id)
                if (idsRequired || o.has("albumId")) require(o.getLong("albumId") == track.albumId)
                for (key in listOf("isSample", "isTrial", "isPreview")) if (o.has(key)) require(o.get(key) == false)
                for (key in listOf("sampleDuration", "sample_duration", "sampleLength", "sample_length")) if (o.has(key)) require(o.getDouble(key) == 0.0)
            }
            validate(json, false)
            val list = json.optJSONArray("fileInfo")
            val selected = if (list != null) {
                require(list.length() in 1..16)
                val entries = (0 until list.length()).map { list.getJSONObject(it) }
                entries.forEach { validate(it, true) }
                listOf("m4a64", "mp364", "m4a24", "mp332").firstNotNullOfOrNull { format -> entries.firstOrNull { it.optString("fileFormat") == format } } ?: error("format")
            } else json.also { validate(it, true) }
            if (selected.opt("isAuthorized") != true || json.has("isAuthorized") && json.get("isAuthorized") != true)
                throw com.shixi.protocol.ContentAccessException(com.shixi.protocol.ListeningAccess.PAID_REQUIRED, "服务端未确认本集完整收听权益（PLAY_PAID_GRANT）")
            val domain = selected.getString("domain").toHttpUrlOrNull() ?: error("domain")
            require(domain.scheme == "https" && domain.host.endsWith(".xmcdn.com") && domain.port == 443 &&
                domain.username.isEmpty() && domain.password.isEmpty() && domain.encodedPath == "/" && domain.query == null && domain.fragment == null)
            val version = selected.getString("apiVersion"); require(version.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){0,3}")))
            val duration = selected.getString("duration"); require(duration.matches(Regex("[0-9]{1,7}(\\.[0-9]{1,3})?")) && duration.toDouble() > 0)
            val file = selected.getString("fileId"); val ep = selected.getString("ep")
            require(file.length in 4..8192 && file.matches(Regex("[A-Za-z0-9+/=\\r\\n]+")))
            require(ep.length in 4..8192 && ep.all { it.code in 33..126 })
            stage = "DECODE"
            val (path, decodedEp) = decode(file, ep)
            stage = "DECODE_FIELDS"
            require(path.length in 1..4096 && !path.contains("preview", true) && !path.contains("trial", true))
            val segments = path.split('/')
            require(segments.all { it.matches(Regex("[A-Za-z0-9_.-]{1,255}")) && it != "." && it != ".." })
            val parts = decodedEp.split('-'); require(parts.size == 4)
            require(parts.take(3).all { it.matches(Regex("[A-Za-z0-9_~+=/]{1,512}")) })
            require(parts[3].matches(Regex("[0-9]{9,13}")))
            return domain.newBuilder().addPathSegment("download").addPathSegment(version).apply { segments.forEach { addPathSegment(it) } }
                .addQueryParameter("buy_key", parts[0]).addQueryParameter("sign", parts[1]).addQueryParameter("token", parts[2])
                .addQueryParameter("timestamp", parts[3]).addQueryParameter("duration", duration).addQueryParameter("uid", uid.toString())
                .addQueryParameter("is_charge", "true").build().toString()
        } catch (failure: com.shixi.protocol.ContentAccessException) { throw failure } catch (failure: ContentServiceException) { throw failure }
        catch (_: Exception) { throw ContentServiceException("获取已授权节目地址失败，请反馈此提示（PLAY_PAID_$stage）") }
    }
}
