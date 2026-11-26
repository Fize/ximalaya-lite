package com.shixi.protocol

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * Read-only client for the verified public discovery endpoints.
 * Every URL, header and required parameter here is recorded in
 * docs/design/discover-redesign/api-spec.md with its probe evidence.
 */
class DiscoveryApi(
    private val transport: ((Request) -> okhttp3.Response)? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        const val UA_MOBILE = "ting_9.4.74.3(Android)"
        const val UA_M = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148"
        const val UA_WEB = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120 Safari/537.36"

        private const val WEB = "https://www.ximalaya.com/revision"
        private const val M = "https://m.ximalaya.com/m-revision/page"
        private const val MOBILE = "https://mobile.ximalaya.com"
        private const val LIVE = "https://live.ximalaya.com"
    }

    // ---- raw bodies (used for disk cache) ----

    fun rawRecommend(cookie: String? = null): String =
        get("$WEB/explore/v2/getRecommend", UA_WEB, cookie)

    fun rawGuessYouLike(cookie: String? = null): String =
        get("$WEB/explore/guessYouLike", UA_WEB, cookie)

    fun rawCategoryBreadcrumb(): String =
        get("$WEB/category/breadcrumbCategoryInfo?categoryId=9", UA_WEB)

    fun rawCategoryAll(): String =
        get("$WEB/category/allCategoryInfo", UA_WEB)

    // ---- typed endpoints ----

    fun recommendFeed(cookie: String? = null): RecommendFeed =
        DiscoveryParsers.recommendFeed(json(rawRecommend(cookie)))

    fun guessYouLike(cookie: String? = null): List<AlbumBrief> =
        DiscoveryParsers.guessYouLike(json(rawGuessYouLike(cookie)))

    fun mobileRecommend(cookie: String? = null): MobileRecommend =
        DiscoveryParsers.mobileRecommend(json(get(
            "$MOBILE/mobile/discovery/v1/recommend/album?device=android&pageId=1&pageSize=6" +
                "&includeActivity=false&includeSpecial=false",
            UA_MOBILE,
            cookie,
        )))

    fun categoryGroups(): List<CategoryGroup> =
        DiscoveryParsers.categoryGroups(json(rawCategoryBreadcrumb()), json(rawCategoryAll()))

    fun categoryPage(code: String): CategoryPage = DiscoveryParsers.categoryPage(getJson(
        "$M/category/queryCategoryPage?categoryCode=${enc(code)}&page=1&pageSize=30&sort=0",
        UA_M,
    ))

    fun categoryAlbums(
        code: String,
        subCode: String? = null,
        sort: Int = 0,
        metas: List<Pair<Long, Long>> = emptyList(),
        page: Int = 1,
        pageSize: Int = 20,
    ): List<AlbumBrief> {
        val query = buildString {
            append("$M/category/queryCategoryAlbumsByPage?categoryCode=").append(enc(code))
            append("&page=").append(page.coerceAtLeast(1))
            append("&pageSize=").append(pageSize.coerceIn(1, 50))
            append("&sort=").append(sort.coerceIn(0, 2))
            if (!subCode.isNullOrBlank()) append("&subCategoryCode=").append(enc(subCode))
            if (metas.isNotEmpty()) {
                append("&metas=").append(metas.joinToString("-") { "${it.first}_${it.second}" })
            }
        }
        return DiscoveryParsers.categoryAlbums(getJson(query, UA_M))
    }

    fun rankGroups(): List<RankGroup> = DiscoveryParsers.rankGroups(getJson("$M/rank/queryRankCluster", UA_M))

    fun rankCategories(clusterCode: String): List<RankCategory> =
        DiscoveryParsers.rankCategories(getJson("$M/rank/queryRankClusterDetail", UA_M), clusterCode)

    fun rankPage(clusterCode: String, categoryCode: String): RankPage {
        // categoryCode is required; an invalid value returns an empty module rather than an error.
        val url = "$M/rank/queryRank?clusterCode=${enc(clusterCode)}&categoryCode=${enc(categoryCode)}"
        return DiscoveryParsers.rankPage(getJson(url, UA_M), clusterCode, categoryCode)
    }

    fun anchorProfile(uid: Long): AnchorProfile? =
        DiscoveryParsers.anchorProfile(getJson("$WEB/user/basic?uid=$uid", UA_WEB))

    fun anchorAlbums(uid: Long): AnchorAlbums =
        DiscoveryParsers.anchorAlbums(getJson("$WEB/user?uid=$uid", UA_WEB))

    fun radioHome(): RadioHome = DiscoveryParsers.radioHome(getJson("$LIVE/radio-first-page-app/homePage", UA_MOBILE))

    fun liveRoom(roomId: Long): LiveRoom? =
        DiscoveryParsers.liveRoom(getJson("$LIVE/lamia/v12/live/room?roomId=$roomId", UA_MOBILE))

    /** `liveId` is recordInfo.id from [liveRoom], never roomId. */
    fun liveStream(liveId: Long): String =
        DiscoveryParsers.liveStream(getJson("$LIVE/lamia-authorize-web/play?liveId=$liveId", UA_MOBILE))

    fun albumDetail(albumId: Long): AlbumDetail? =
        DiscoveryParsers.albumDetail(getJson("$WEB/album?albumId=$albumId", UA_WEB))

    // ---- transport ----

    fun get(url: String, userAgent: String, cookie: String? = null): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json,text/plain,*/*")
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie).header("Cookie2", "\$version=1")
        }
        val response = (transport?.invoke(builder.build()) ?: client.newCall(builder.build()).execute())
        response.use {
            if (!it.isSuccessful) throw DiscoveryException("内容服务 HTTP ${it.code}")
            val body = it.body.string()
            if (body.isBlank()) throw DiscoveryException("内容服务返回空响应")
            // Validate the envelope once so every parser can assume a successful body.
            val ret = runCatching { JSONObject(body).opt("ret") }.getOrNull()
            val ok = ret == null || when (ret) {
                is Number -> ret.toInt() == 0 || ret.toInt() == 200
                else -> ret.toString() in setOf("0", "200", "0000", "success")
            }
            if (!ok) {
                val message = runCatching { JSONObject(body).optString("msg") }.getOrDefault("")
                throw DiscoveryException(if (message.isBlank()) "内容服务拒绝请求" else message)
            }
            return body
        }
    }

    private fun getJson(url: String, userAgent: String, cookie: String? = null): JSONObject =
        json(get(url, userAgent, cookie))

    private fun json(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw DiscoveryException("内容服务返回了不支持的格式")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
