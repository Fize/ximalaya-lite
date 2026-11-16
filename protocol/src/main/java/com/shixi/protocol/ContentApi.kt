package com.shixi.protocol

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class ListeningAccess(val label: String) {
    FREE("免费收听"), AUTHORIZED("已获收听授权"), VIP_REQUIRED("VIP 内容 · 需要会员权益"),
    PAID_REQUIRED("付费内容 · 需要收听权益"), TRIAL("试听内容 · 暂不支持试听播放"),
    UNAVAILABLE("暂不可收听"), UNKNOWN("权限待确认")
}

data class Item(val id: Long, val title: String, val free: Boolean = false, val albumId: Long = 0,
    val cover: String = "", val page: Int = 1,
    val access: ListeningAccess = if (free) ListeningAccess.FREE else ListeningAccess.UNKNOWN) : java.io.Serializable {
    val playable: Boolean get() = access == ListeningAccess.FREE || access == ListeningAccess.AUTHORIZED
}

/** Content type and an explicit server grant are separate; a paid album can have free episodes. */
object EpisodeAccess {
    fun classify(o: JSONObject, vipAlbum: Boolean = false): ListeningAccess {
        if (o.opt("isHoldCopyright") == false || o.opt("isPublic") == false) return ListeningAccess.UNAVAILABLE
        if (o.opt("isSample") == true) return ListeningAccess.TRIAL
        if (o.opt("isPaid") == false && o.opt("isHoldCopyright") == true && o.opt("isSample") == false)
            return ListeningAccess.FREE
        if (o.opt("isPaid") == true) {
            if (o.opt("isFree") == true && o.opt("isAuthorized") == true) return ListeningAccess.FREE
            if (o.opt("isAuthorized") == true) return ListeningAccess.AUTHORIZED
            // Type 1 is confirmed by live list/baseInfo and search is_vip=true for the same albums.
            if (vipAlbum || o.opt("isVipFree") == true || o.optInt("vipFreeType") == 1) return ListeningAccess.VIP_REQUIRED
            return ListeningAccess.PAID_REQUIRED
        }
        return ListeningAccess.UNKNOWN
    }
}
class ContentAccessException(val access: ListeningAccess, detail: String = access.label, val serviceCode: Int? = null) : java.io.IOException(detail)
class ContentServiceException(detail: String) : java.io.IOException(detail)
/** Product copy never exposes raw HTTP responses, credentials, or implementation diagnostics. */
fun userContentMessage(failure: Exception): String = when (failure) {
    is ContentAccessException -> when (failure.access) {
        ListeningAccess.UNAVAILABLE -> "该内容暂不可收听"
        ListeningAccess.TRIAL -> "暂不支持本集试听"
        ListeningAccess.VIP_REQUIRED, ListeningAccess.PAID_REQUIRED -> "当前账号暂无本集收听权益"
        else -> if (failure.serviceCode in setOf(50, 401, 403)) "登录状态需要更新，请重试" else "账号状态已变化，请重新选择节目"
    }
    is java.net.SocketTimeoutException -> "连接超时，请重试"
    is ContentServiceException -> "内容服务暂时不可用，请稍后重试"
    is java.io.IOException -> "连接失败，请检查网络后重试"
    else -> "加载失败，请重试"
}
/** Only fixed, non-sensitive descriptions reach the entitlement dialog. */
fun listeningFailureMessage(failure: Exception): String = userContentMessage(failure)

data class EpisodeComment(val id: Long, val author: String, val content: String, val createdAt: Long, val likes: Int, val avatar: String = "")
data class EpisodeComments(val rows: List<EpisodeComment>, val hasMore: Boolean, val totalCount: Long? = null)

class ContentApi(private val paidAddress: ((Item) -> String)? = null, private val transport: ((Request) -> okhttp3.Response)? = null) {
    private val client = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private val redirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private fun read(request: Request): JSONObject {
        val stage = when {
            request.url.encodedPath.startsWith("/mobile/v1/album/track/") -> "读取节目列表"
            request.url.encodedPath == "/mobile/v1/album/info" -> "读取专辑资料"
            request.url.encodedPath == "/v1/track/baseInfo" -> "检查节目播放信息"
            else -> "搜索节目"
        }
        (transport?.invoke(request) ?: client.newCall(request).execute()).use { response ->
            if (response.code in setOf(401, 403)) throw ContentAccessException(ListeningAccess.UNKNOWN, "账号授权未通过，请到账号页校验登录状态后重试", response.code)
            if (!response.isSuccessful) throw ContentServiceException("$stage：内容服务 HTTP ${response.code}，请稍后重试")
            val json = try { JSONObject(response.body.string()) }
                catch (_: org.json.JSONException) { throw ContentServiceException("$stage：服务端响应格式不支持（CONTENT_JSON）") }
            val ret = json.optInt("ret", 0)
            if (ret != 0) throw when (ret) {
                // The shipped client's RequestError defines 924 as offline, not login failure.
                // 929 is also returned with an offline message by the live metadata endpoint.
                924, 929, 702 -> ContentAccessException(ListeningAccess.UNAVAILABLE, "$stage：服务端返回此内容暂不可用（$ret），请核对所选节目或稍后重试", ret)
                50 -> ContentAccessException(ListeningAccess.UNKNOWN, "账号授权未通过（$ret），请到账号页校验登录状态后重试", ret)
                else -> ContentAccessException(ListeningAccess.UNKNOWN, "$stage：内容服务拒绝请求（$ret），尚未确认此节目的收听权益")
            }
            return json
        }
    }
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "ting_9.4.74.3(Android)")
    fun episodeComments(trackId: Long, page: Int = 1): EpisodeComments {
        require(trackId > 0 && page > 0)
        val data = read(request("https://mobile.ximalaya.com/comment-mobile/v1/track/comment?trackId=$trackId&pageId=$page&pageSize=20&order=0").build())
        val comments = data.optJSONObject("comment") ?: throw ContentServiceException("评论暂不可用")
        val rows = comments.optJSONArray("list") ?: throw ContentServiceException("评论暂不可用")
        val parsed = (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let { row ->
            val id = row.optLong("id"); val content = row.optString("content").trim()
            if (id <= 0 || content.isEmpty()) null else EpisodeComment(id, row.optString("nickname").ifBlank { "听友" }, content, row.optLong("createdAt"), row.optInt("likes").coerceAtLeast(0), row.optString("smallHeader").replaceFirst("http://", "https://"))
        } }
        return EpisodeComments(parsed, page < comments.optInt("maxPageId", page), comments.optLong("totalCount", -1).takeIf { it >= 0 })
    }
    fun albumDetails(albumId: Long): Item {
        require(albumId > 0)
        val info = read(request("https://mobile.ximalaya.com/mobile/v1/album/info?albumId=$albumId&device=android").build())
        if (info.optLong("albumId") != albumId) throw ContentServiceException("专辑资料不匹配")
        val title = info.optString("title").takeIf { it.isNotBlank() } ?: throw ContentServiceException("专辑资料不完整")
        val cover = info.optString("coverOrigin").ifBlank { info.optString("coverLarge") }.replaceFirst("http://", "https://")
        return Item(albumId, title, cover = cover)
    }
    fun search(query: String, page: Int = 1): List<Item> {
        require(page > 0)
        require(query.isNotBlank()) { "请输入关键词" }
        val body = JSONObject().put("kw", query).put("core", "album").put("page", page).put("rows", 20).toString()
        val data = read(request("https://search.ximalaya.com/hub/front/v1").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        val response = data.optJSONObject("response") ?: error("搜索响应格式不支持")
        val docs = response.optJSONArray("docs") ?: run {
            if (response.length() == 0 && data.optString("reason") == "no recall") return emptyList()
            error("搜索响应格式不支持")
        }
        return (0 until docs.length()).mapNotNull { i -> docs.optJSONObject(i)?.let { o -> Item(o.optLong("id"), o.optString("title"), cover = o.optString("cover_path").replaceFirst("http://", "https://")) } }.filter { it.id > 0 && it.title.isNotBlank() }
    }
    private fun catalogue(albumId: Long, page: Int): org.json.JSONArray {
        val response = read(request("https://mobile.ximalaya.com/mobile/others/ca/album/track/$albumId/true/$page/20?device=android").build())
        if (response.optJSONObject("album")?.optLong("albumId") != albumId)
            throw ContentServiceException("专辑资料不匹配")
        return response.optJSONObject("tracks")?.optJSONArray("list") ?: throw ContentServiceException("节目列表格式不支持")
    }
    private fun legacyUnavailable(failure: ContentAccessException) = failure.serviceCode in setOf(924, 929)
    fun tracks(albumId: Long, page: Int = 1): List<Item> {
        require(page > 0)
        require(albumId > 0)
        val url = "https://mobile.ximalaya.com/mobile/v1/album/track/ts-${System.currentTimeMillis()}?albumId=$albumId&pageId=$page&pageSize=20&isAsc=true&device=android"
        val list = try { read(request(url).build()).optJSONObject("data")?.optJSONArray("list") ?: error("节目列表响应格式不支持") }
            catch (failure: ContentAccessException) { if (!legacyUnavailable(failure)) throw failure; catalogue(albumId, page) }
        // Album metadata is only a content-type hint, never inherited as a per-episode grant.
        val hasPaid = (0 until list.length()).any { list.optJSONObject(it)?.opt("isPaid") == true }
        val vipAlbum = if (hasPaid) runCatching {
            val info = read(request("https://mobile.ximalaya.com/mobile/v1/album/info?albumId=$albumId&device=android").build())
            info.optLong("albumId") == albumId && (info.opt("isVipFree") == true || info.optInt("vipFreeType") == 1)
        }.getOrDefault(false) else false
        return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { o ->
            val access = EpisodeAccess.classify(o, vipAlbum)
            Item(o.optLong("trackId"), o.optString("title"), access == ListeningAccess.FREE, albumId,
                cover = o.optString("coverMiddle").replaceFirst("http://", "https://"), page = page, access = access)
        } }.filter { it.id > 0 && it.title.isNotBlank() }
    }
    fun locateTrack(albumId: Long, trackId: Long): Item {
        require(albumId > 0 && trackId > 0)
        for (page in 1..100) {
            val rows = tracks(albumId, page)
            rows.firstOrNull { it.id == trackId }?.let { return it }
            if (rows.size < 20) break
        }
        error("未能在专辑中定位该节目，请进入专辑重新选择")
    }
    fun resolve(track: Item): String {
        require(track.id > 0 && track.albumId > 0)
        // Fetch fresh per-episode metadata. Never trust a restored UI/queue flag as an entitlement.
        val info = try { read(request("https://mobile.ximalaya.com/v1/track/baseInfo?trackId=${track.id}&device=android").build()) }
            catch (failure: ContentAccessException) {
                if (!legacyUnavailable(failure)) throw failure
                // Fresh account-bound response, exact episode only. A URL alone is never a grant.
                val rows = catalogue(track.albumId, track.page)
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull {
                    it.optLong("trackId") == track.id && it.optLong("albumId") == track.albumId
                } ?: throw ContentServiceException("节目位置已变化，请从专辑重新选择")
            }
        if (info.optLong("trackId") != track.id || info.optLong("albumId") != track.albumId)
            throw ContentServiceException("播放资料与所选节目不匹配，请返回专辑重新选择（PLAY_METADATA_MISMATCH）")
        if (info.opt("isPublic") == false || info.opt("isHoldCopyright") == false || info.optInt("status", 1) != 1)
            throw ContentAccessException(ListeningAccess.UNAVAILABLE)
        if (info.opt("isSample") == true || info.opt("isTrailer") == true) throw ContentAccessException(ListeningAccess.TRIAL)
        val paid = info.opt("isPaid")
        val granted = if (paid == false) track.free && EpisodeAccess.classify(info) !in setOf(ListeningAccess.TRIAL, ListeningAccess.UNAVAILABLE)
            else paid == true && info.opt("isAuthorized") == true
        if (!granted) throw ContentAccessException(EpisodeAccess.classify(info))
        // Unlike tracks/{id}.json, this endpoint also returns playable URLs for free prefixes.
        val raw = info.optString("playUrl64").takeUnless { it == "null" }.orEmpty()
            .ifBlank { info.optString("playUrl32").takeUnless { it == "null" }.orEmpty() }
        if (raw.isBlank()) {
            if (paid == true && paidAddress != null) return paidAddress.invoke(track)
            val grantedMessage = if (paid == true) "服务端已确认本集收听授权，" else ""
            if ((info.optJSONArray("playUrlList")?.length() ?: 0) > 0)
                throw ContentServiceException("${grantedMessage}服务端返回了当前客户端尚未支持的播放地址列表，请反馈此提示（PLAY_URL_LIST_UNSUPPORTED）")
            throw ContentServiceException("${grantedMessage}服务端未返回当前客户端可用的播放地址，请反馈此提示（PLAY_URL_MISSING）")
        }
        val url = raw.toHttpUrlOrNull()
            ?: throw ContentServiceException("服务端播放地址格式不支持，请反馈此提示（PLAY_URL_INVALID）")
        // Upgrade known first-party redirect/CDN hosts; never permit cleartext media.
        val secure = if (url.scheme == "http" && (url.host == "mobile.ximalaya.com" || url.host.endsWith(".xmcdn.com")))
            url.newBuilder().scheme("https").build() else url
        if (secure.scheme != "https") throw ContentServiceException("服务端未返回安全的播放地址（PLAY_URL_INSECURE）")
        if (secure.host == "mobile.ximalaya.com" && secure.encodedPath.startsWith("/mobile/redirect/")) {
            // The service returns an HTTP CDN Location even when requested over HTTPS. Resolve it
            // here and upgrade the known CDN instead of permitting a media HTTPS→HTTP downgrade.
            (transport?.invoke(request(secure.toString()).header("Range", "bytes=0-0").build())
                ?: redirects.newCall(request(secure.toString()).header("Range", "bytes=0-0").build()).execute()).use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val target = response.header("Location")?.let(secure::resolve)
                        ?: throw ContentServiceException("播放服务未返回有效跳转地址（PLAY_REDIRECT_MISSING）")
                    if (!target.host.endsWith(".xmcdn.com") || target.username.isNotEmpty() || target.password.isNotEmpty())
                        throw ContentServiceException("播放跳转地址不支持（PLAY_REDIRECT_HOST）")
                    val media = if (target.scheme == "http") target.newBuilder().scheme("https").build() else target
                    if (media.scheme != "https") throw ContentServiceException("播放跳转未返回安全地址（PLAY_URL_INSECURE）")
                    return media.toString()
                }
                if (response.code == 401 || response.code == 403 ||
                    response.isSuccessful && response.header("Content-Type").orEmpty().contains("json")) {
                    throw ContentAccessException(if (paid == true) {
                        if (info.optInt("vipFreeType") == 1 || info.opt("isVipFree") == true) ListeningAccess.VIP_REQUIRED
                        else ListeningAccess.PAID_REQUIRED
                    } else ListeningAccess.UNAVAILABLE)
                }
                if (!response.isSuccessful) throw ContentServiceException("获取播放地址：内容服务 HTTP ${response.code}，请稍后重试（PLAY_REDIRECT_HTTP）")
                if (!response.header("Content-Type").orEmpty().startsWith("audio/"))
                    throw ContentServiceException("播放跳转响应格式不支持（PLAY_REDIRECT_FORMAT）")
            }
        }
        return secure.toString()
    }
}
