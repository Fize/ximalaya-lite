package com.shixi.protocol

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fixture-backed contract tests for the verified discovery endpoints.
 * Fixtures are real responses captured on 2026-11-26 (see api-spec.md).
 */
class DiscoveryParsersTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("discovery/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun json(name: String): JSONObject = JSONObject(load(name))

    private fun response(body: String, code: Int = 200): Response = Response.Builder()
        .request(okhttp3.Request.Builder().url("https://example.invalid/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fixture")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    // ---- cover normalisation ----

    @Test
    fun coverAcceptsEveryObservedShape() {
        assertEquals("", DiscoveryParsers.cover(""))
        assertEquals("", DiscoveryParsers.cover("null"))
        assertEquals("https://imagev2.xmcdn.com/group42/a.jpg", DiscoveryParsers.cover("group42/a.jpg"))
        assertEquals("https://imagev2.xmcdn.com/x.jpg", DiscoveryParsers.cover("//imagev2.xmcdn.com/x.jpg"))
        assertEquals("https://imagev2.xmcdn.com/x.jpg", DiscoveryParsers.cover("http://imagev2.xmcdn.com/x.jpg"))
        assertEquals("https://imagev2.xmcdn.com/x.jpg", DiscoveryParsers.cover("https://imagev2.xmcdn.com/x.jpg"))
    }

    // ---- recommendation ----

    @Test
    fun webRecommendationKeepsFiveSectionsWithPlayableAlbums() {
        val feed = DiscoveryParsers.recommendFeed(json("recommend_web.json"))
        assertEquals(5, feed.sections.size)
        assertEquals(listOf("有声书", "相声评书", "儿童", "头条", "娱乐"), feed.sections.map { it.title })
        feed.sections.forEach { section ->
            assertTrue(section.categoryCode.isNotBlank())
            assertTrue(section.albums.isNotEmpty())
            assertTrue(section.rankTitle.contains("榜"))
        }
        val first = feed.sections.first()
        assertTrue(first.hotWords.isNotEmpty())
        val album = first.albums.first()
        assertTrue(album.valid)
        assertTrue(album.cover.startsWith("https://"))
        assertTrue(album.title.isNotBlank())
        assertNotNull(album.playCount)
        assertNotNull(album.trackCount)
        assertTrue(first.albums.any { it.anchorName.isNotBlank() })
    }

    @Test
    fun guessYouLikeReturnsTheDocumentedStaticPage() {
        val rows = DiscoveryParsers.guessYouLike(json("guess_you_like.json"))
        assertEquals(52, rows.size)
        assertTrue(rows.all { it.valid })
        assertTrue(rows.all { it.albumId > 0 })
        assertTrue(rows.count { it.anchorName.isNotBlank() } >= 45)
    }

    @Test
    fun mobileRecommendationDropsAdsAndPlaylistsButKeepsLiveRooms() {
        val feed = DiscoveryParsers.mobileRecommend(json("mobile_recommend.json"))
        assertEquals(6, feed.albums.size)
        assertEquals(2, feed.categorySections.size)
        assertEquals(3, feed.liveRooms.size)
        assertTrue(feed.liveRooms.all { it.roomId > 0 && it.liveId > 0 && it.name.isNotBlank() })
        // No parser path can surface an ad or a playlist item.
        assertFalse(feed.categorySections.any { it.title == "广告" })
    }

    @Test
    fun adModuleContentNeverReachesTheUiModel() {
        val body = JSONObject(
            """{"ret":0,"list":[
                 {"moduleType":"ad","list":[{"albumId":1,"title":"sponsored"}]},
                 {"moduleType":"guessYouLike","list":[{"albumId":7,"title":"real","pic":"p.jpg"}]}
               ]}"""
        )
        val feed = DiscoveryParsers.mobileRecommend(body)
        assertEquals(listOf(7L), feed.albums.map { it.albumId })
    }

    // ---- categories ----

    @Test
    fun categoryGroupsMergeNavigationAndWebGroups() {
        val groups = DiscoveryParsers.categoryGroups(json("category_breadcrumb.json"), json("category_all.json"))
        val nodes = groups.flatMap { it.categories }
        assertEquals(29, nodes.size)
        assertEquals(nodes.map { it.id }.distinct().size, nodes.size)
        assertEquals(listOf("有声小说", "娱乐", "知识", "生活", "特色", "其他"), groups.map { it.title })
        val history = nodes.first { it.title == "历史" }
        assertEquals("lishi", history.code)
        assertTrue(nodes.all { it.code.isNotBlank() && it.title.isNotBlank() })
    }

    @Test
    fun categoryPageExposesSubCategoriesFiltersAndAlbums() {
        val page = DiscoveryParsers.categoryPage(json("category_page.json"))
        assertEquals(9, page.categoryId)
        assertEquals("历史", page.title)
        assertEquals(24, page.subCategories.size)
        assertEquals(5, page.filters.size)
        assertEquals(30, page.albums.size)
        assertTrue(page.albums.all { it.valid })
        assertTrue(page.filters.any { it.title == "是否完本" && it.values.any { value -> value.title == "已完本" } })
        val album = page.albums.first()
        assertTrue(album.cover.startsWith("https://"))
        assertNotNull(album.paid)
        assertNotNull(album.finished)
    }

    @Test
    fun filteredCategoryPageHonoursFinishedAndFree() {
        val albums = DiscoveryParsers.categoryAlbums(json("category_albums_p2.json"))
        assertEquals(20, albums.size)
        assertTrue(albums.all { it.valid })
        assertTrue(albums.all { it.finished == true })
        assertTrue(albums.all { it.paid == false })
    }

    // ---- ranks ----

    @Test
    fun rankGroupsAndCategoriesMatchProbe() {
        val groups = DiscoveryParsers.rankGroups(json("rank_clusters.json"))
        assertEquals(listOf("hotplay", "newRanking", "hotSale", "publicPraise", "anchorInfluence", "traditionalChineseCulture"), groups.map { it.code })
        val detail = json("rank_cluster_detail.json")
        val hotplay = DiscoveryParsers.rankCategories(detail, "hotplay")
        assertEquals(27, hotplay.size)
        assertTrue(hotplay.any { it.code == "all" && it.rankId == 47L })
        val anchors = DiscoveryParsers.rankCategories(detail, "anchorInfluence")
        assertEquals(6, anchors.size)
        assertTrue(anchors.any { it.code == "voiceActor74" && it.title == "有声书主播" })
    }

    @Test
    fun albumRankParsesPositionsAndMetadata() {
        val page = DiscoveryParsers.rankPage(json("rank_hotplay.json"), "hotplay", "all")
        assertEquals("热播榜", page.title)
        assertEquals(97, page.entries.size)
        assertEquals((1..97).toList(), page.entries.map { it.position })
        assertTrue(page.entries.all { it.album != null && it.album.valid && it.anchor == null })
        assertNotNull(page.entries.first().album?.playCount)
    }

    @Test
    fun anchorRankParsesAnchorProfiles() {
        val page = DiscoveryParsers.rankPage(json("rank_anchor.json"), "anchorInfluence", "voiceActor74")
        assertEquals("有声书主播榜", page.title)
        assertEquals(100, page.entries.size)
        assertTrue(page.entries.all { entry -> entry.anchor?.valid == true && entry.album == null })
        val first = page.entries.first().anchor!!
        assertTrue(first.name.isNotBlank())
        assertTrue(first.avatar.startsWith("https://"))
        assertNotNull(first.fans)
    }

    // ---- anchors ----

    @Test
    fun anchorProfileAndAlbumsParse() {
        val profile = DiscoveryParsers.anchorProfile(json("anchor_basic.json"))
        assertNotNull(profile)
        assertEquals(1266964L, profile!!.uid)
        assertEquals("有声的紫襟", profile.name)
        assertTrue(profile.fans!! > 0)
        assertTrue(profile.avatar.startsWith("https://"))

        val albums = DiscoveryParsers.anchorAlbums(json("anchor_albums.json"))
        assertEquals(120, albums.total)
        assertEquals(15, albums.albums.size)
        assertTrue(albums.albums.all { it.valid })
        assertTrue(albums.albums.first().cover.startsWith("https://"))
    }

    // ---- radio / live ----

    @Test
    fun radioHomeExposesPlayableHlsStations() {
        val home = DiscoveryParsers.radioHome(json("radio_home.json"))
        assertEquals("北京", home.location)
        assertEquals(listOf("头部调频", "找电台", "本地电台"), home.modules.map { it.title })
        assertEquals(27, home.modules.sumOf { it.stations.size })
        assertTrue(home.categories.isNotEmpty())
        assertTrue(home.locations.isNotEmpty())
        home.modules.forEach { module ->
            module.stations.forEach { station ->
                assertTrue(station.id > 0)
                assertTrue(station.name.isNotBlank())
                assertTrue(station.streamUrl.startsWith("https://"))
                assertTrue(station.streamUrl.endsWith(".m3u8"))
            }
        }
    }

    @Test
    fun liveRoomAndStreamParse() {
        val room = DiscoveryParsers.liveRoom(json("live_room.json"))
        assertNotNull(room)
        assertEquals(382985L, room!!.roomId)
        assertEquals(35021290L, room.liveId)
        assertTrue(room.name.isNotBlank())
        assertEquals("姣姣兮", room.anchor)

        val stream = DiscoveryParsers.liveStream(json("live_stream.json"))
        assertTrue(stream.startsWith("https://"))
        assertTrue(stream.endsWith(".m3u8"))
    }

    // ---- album detail ----

    @Test
    fun albumDetailAddsAnchorAndPlayMetadata() {
        val detail = DiscoveryParsers.albumDetail(json("album_detail.json"))
        assertNotNull(detail)
        assertEquals(70781065L, detail!!.albumId)
        assertTrue(detail.title.isNotBlank())
        assertTrue(detail.anchorName.isNotBlank())
        assertTrue(detail.playCount!! > 0)
        assertTrue(detail.trackCount!! > 0)
        assertTrue(detail.cover.startsWith("https://"))
    }

    // ---- transport ----

    @Test
    fun nonZeroReturnCodeBecomesDiscoveryFailure() {
        val api = DiscoveryApi(transport = { response("""{"ret":500,"msg":"服务器内部错误"}""") })
        try {
            api.recommendFeed()
            fail("expected DiscoveryException")
        } catch (expected: DiscoveryException) {
            assertEquals("服务器内部错误", expected.message)
        }
    }

    @Test
    fun httpErrorBecomesDiscoveryFailure() {
        val api = DiscoveryApi(transport = { response("""{"ret":0}""", code = 503) })
        try {
            api.rankGroups()
            fail("expected DiscoveryException")
        } catch (expected: DiscoveryException) {
            assertTrue(expected.message!!.contains("503"))
        }
    }

    @Test
    fun categoryQueryEncodesVerifiedParameters() {
        val captured = StringBuilder()
        val api = DiscoveryApi(transport = { request ->
            captured.append(request.url.toString())
            response("""{"ret":0,"data":{"albumBriefDetailInfos":[]}}""")
        })
        api.categoryAlbums(
            code = "lishi",
            subCode = "zhengshi",
            sort = 2,
            metas = listOf(131L to 2560L, 132L to 2722L),
            page = 3,
        )
        val url = captured.toString()
        assertTrue(url.contains("categoryCode=lishi"))
        assertTrue(url.contains("page=3"))
        assertTrue(url.contains("sort=2"))
        assertTrue(url.contains("subCategoryCode=zhengshi"))
        assertTrue(url.contains("metas=131_2560-132_2722"))
    }
}
