package com.shixi.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsers for the verified public endpoints. They never invent values:
 * missing fields stay null/empty and malformed rows are dropped instead of
 * being replaced with placeholders.
 */
object DiscoveryParsers {

    /** First-party covers arrive as absolute, protocol-relative or bare CDN paths. */
    fun cover(raw: String): String {
        val value = raw.trim()
        return when {
            value.isEmpty() || value == "null" -> ""
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            value.startsWith("https://") -> value
            else -> "https://imagev2.xmcdn.com/" + value
        }
    }

    private fun JSONObject.text(key: String): String =
        optString(key).let { if (it == "null") "" else it }.trim()

    private fun JSONArray?.objects(): List<JSONObject> = when {
        this == null -> emptyList()
        else -> (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun album(
        id: Long,
        title: String,
        rawCover: String,
        anchorId: Long = 0,
        anchorName: String = "",
        plays: Long? = null,
        tracks: Int? = null,
        paid: Boolean? = null,
        vipFree: Boolean? = null,
        finished: Boolean? = null,
        subtitle: String = "",
    ) = AlbumBrief(id, title.trim(), cover(rawCover), anchorId, anchorName.trim(), plays, tracks, paid, vipFree, finished, subtitle.trim())

    /** Category list rows: albumInfo + anchorInfo + statCountInfo. */
    fun categoryAlbum(row: JSONObject): AlbumBrief? {
        val info = row.optJSONObject("albumInfo") ?: return null
        val id = info.optLong("id")
        val title = info.text("title")
        if (id <= 0 || title.isBlank()) return null
        val anchor = row.optJSONObject("anchorInfo")
        val stat = row.optJSONObject("statCountInfo")
        val finishTag = info.optInt("albumFinishTag", -1)
        return album(
            id = id,
            title = title,
            rawCover = info.text("cover"),
            anchorId = anchor?.optLong("id") ?: 0,
            anchorName = anchor?.text("nickname").orEmpty(),
            plays = stat?.optLong("playCount")?.takeIf { it > 0 },
            tracks = stat?.optInt("trackCount")?.takeIf { it > 0 },
            paid = info.optInt("albumPayType", -1).let { if (it < 0) null else it == 1 },
            vipFree = info.optInt("albumVipPayType", -1).let { if (it < 0) null else it == 2 },
            finished = if (finishTag < 0) null else finishTag == 2,
            subtitle = info.text("recommendReason").ifBlank { info.text("customTitle") },
        )
    }

    /** Web explore recommendation rows. */
    fun webAlbum(row: JSONObject): AlbumBrief? {
        val id = row.optLong("albumId")
        val title = row.text("albumTitle")
        if (id <= 0 || title.isBlank()) return null
        return album(
            id = id,
            title = title,
            rawCover = row.text("albumCoverPath"),
            anchorId = row.optLong("anchorId"),
            anchorName = row.text("albumUserNickName"),
            plays = row.optLong("albumPlayCount").takeIf { it > 0 },
            tracks = row.optInt("albumTrackCount").takeIf { it > 0 },
            paid = if (row.has("isPaid")) row.optBoolean("isPaid") else null,
            // isFinished semantics differ between web and category payloads; do not guess.
            subtitle = row.text("intro"),
        )
    }

    /** Mobile discovery rows (`guessYouLike`, `categoriesForExplore`, `cityCategory`). */
    fun mobileAlbum(row: JSONObject): AlbumBrief? {
        val id = row.optLong("albumId")
        val title = row.text("title")
        if (id <= 0 || title.isBlank()) return null
        return album(
            id = id,
            title = title,
            rawCover = row.text("pic"),
            plays = row.optLong("playsCount").takeIf { it > 0 },
            tracks = row.optInt("tracksCount").takeIf { it > 0 },
            paid = if (row.has("isPaid")) row.optBoolean("isPaid") else null,
            vipFree = if (row.has("isVipFree")) row.optBoolean("isVipFree") else null,
            subtitle = row.text("subtitle"),
        )
    }

    fun recommendFeed(json: JSONObject): RecommendFeed {
        val cards = json.optJSONObject("data")?.optJSONArray("cards").objects()
        val sections = cards.mapNotNull { card ->
            val albums = card.optJSONArray("albumList").objects().mapNotNull(::webAlbum)
            if (albums.isEmpty()) return@mapNotNull null
            RecommendSection(
                title = card.text("title"),
                categoryId = card.optInt("categoryId"),
                categoryCode = card.text("name"),
                albums = albums,
                hotWords = card.optJSONArray("hotWord").let { array ->
                    if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                },
                rankTitle = card.text("rankTitle"),
            )
        }
        return RecommendFeed(sections = sections)
    }

    fun guessYouLike(json: JSONObject): List<AlbumBrief> =
        json.optJSONObject("data")?.optJSONArray("recommendInfoList").objects()
            .mapNotNull(::webAlbum)
            .distinctBy { it.albumId }

    fun mobileRecommend(json: JSONObject): MobileRecommend {
        val modules = json.optJSONArray("list").objects()
        val albums = mutableListOf<AlbumBrief>()
        val sections = mutableListOf<RecommendSection>()
        val rooms = mutableListOf<LiveRoom>()
        modules.forEach { module ->
            when (module.optString("moduleType")) {
                "guessYouLike" -> albums += module.optJSONArray("list").objects().mapNotNull(::mobileAlbum)
                "categoriesForExplore", "cityCategory" -> {
                    val rows = module.optJSONArray("list").objects().mapNotNull(::mobileAlbum)
                    if (rows.isNotEmpty()) sections += RecommendSection(
                        title = module.text("title"),
                        categoryId = module.optInt("categoryId"),
                        categoryCode = "",
                        albums = rows,
                    )
                }
                "live" -> rooms += module.optJSONArray("list").objects().mapNotNull(::feedRoom)
                // "ad" is never parsed. "playlist" has no verified detail endpoint.
            }
        }
        return MobileRecommend(albums = albums.distinctBy { it.albumId }, categorySections = sections, liveRooms = rooms)
    }

    private fun feedRoom(row: JSONObject): LiveRoom? {
        val roomId = row.optLong("roomId")
        val liveId = row.optLong("id")
        val name = row.text("name")
        if (roomId <= 0 || liveId <= 0 || name.isBlank()) return null
        // status=9 observed for both live rooms returned by the live module.
        return LiveRoom(
            roomId = roomId,
            liveId = liveId,
            name = name,
            anchor = row.text("nickname"),
            anchorUid = row.optLong("uid"),
            cover = cover(row.text("coverMiddle")),
            online = row.optInt("playCount"),
            alive = row.optInt("status", -1) == 9,
            category = row.text("categoryName"),
        )
    }

    /** Flat pinyin navigation list (29 categories) merged with the 5 web groups. */
    fun categoryGroups(breadcrumb: JSONObject, all: JSONObject): List<CategoryGroup> {
        val nodes = breadcrumb.optJSONObject("data")?.optJSONArray("categories").objects()
            .mapNotNull { row ->
                val id = row.optInt("id")
                val title = row.text("displayName")
                val code = row.text("pinyin")
                if (id <= 0 || title.isBlank() || code.isBlank()) null else CategoryNode(id, title, code)
            }
            .distinctBy { it.id }
        if (nodes.isEmpty()) return emptyList()
        val byId = nodes.associateBy { it.id }
        val groups = all.optJSONArray("data").objects().mapNotNull { group ->
            val categories = group.optJSONArray("categories").objects().mapNotNull { byId[it.optInt("id")] }
            if (categories.isEmpty()) null else CategoryGroup(group.text("name"), categories)
        }
        val used = groups.flatMap { it.categories }.map { it.id }.toSet()
        val rest = nodes.filter { it.id !in used }
        return if (rest.isEmpty()) groups else groups + CategoryGroup("其他", rest)
    }

    private fun subCategories(data: JSONObject): List<SubCategory> =
        data.optJSONArray("subCategories").objects().mapNotNull { row ->
            val code = row.text("code")
            val title = row.text("displayValue")
            if (code.isBlank() || title.isBlank()) null else SubCategory(row.optLong("subCategoryId"), code, title)
        }

    private fun filters(data: JSONObject): List<FilterDimension> =
        data.optJSONArray("categoryMetaDatas").objects().mapNotNull { dimension ->
            val id = dimension.optLong("id")
            val title = dimension.text("name")
            val values = dimension.optJSONArray("metaValues").objects().mapNotNull { value ->
                val valueId = value.optLong("id")
                val label = value.text("displayName").ifBlank { value.text("name") }
                if (valueId <= 0 || label.isBlank()) null else FilterValue(valueId, label)
            }
            if (id <= 0 || title.isBlank() || values.isEmpty()) null else FilterDimension(id, title, values)
        }

    fun categoryPage(json: JSONObject): CategoryPage {
        val data = json.optJSONObject("data") ?: return CategoryPage(0, "")
        val page = data.optJSONObject("firstPageCategoryAlbums")
        return CategoryPage(
            categoryId = data.optInt("categoryId"),
            title = data.text("categoryDisplayName"),
            subCategories = subCategories(data),
            filters = filters(data),
            albums = page?.optJSONArray("albumBriefDetailInfos").objects().mapNotNull(::categoryAlbum).orEmpty(),
            total = page?.optInt("totalCount") ?: 0,
            page = page?.optInt("page") ?: 1,
        )
    }

    fun categoryAlbums(json: JSONObject): List<AlbumBrief> =
        json.optJSONObject("data")?.optJSONArray("albumBriefDetailInfos").objects().mapNotNull(::categoryAlbum).orEmpty()

    fun rankGroups(json: JSONObject): List<RankGroup> =
        json.optJSONArray("data").objects().mapNotNull { row ->
            val code = row.text("clusterCode")
            val title = row.text("clusterName")
            if (code.isBlank() || title.isBlank()) null else RankGroup(code, title)
        }

    fun rankCategories(json: JSONObject, clusterCode: String): List<RankCategory> {
        val cluster = json.optJSONArray("data").objects().firstOrNull { it.text("clusterCode") == clusterCode } ?: return emptyList()
        return cluster.optJSONArray("categoryInfos").objects().mapNotNull { row ->
            val code = row.text("categoryCode")
            val title = row.text("title")
            if (code.isBlank() || title.isBlank()) null else RankCategory(title, code, row.optLong("rankId"))
        }
    }

    fun rankPage(json: JSONObject, clusterCode: String, categoryCode: String): RankPage {
        val data = json.optJSONObject("data") ?: return RankPage(clusterCode, categoryCode, "")
        val entries = data.optJSONArray("rankModuleInfoList").objects().mapIndexedNotNull { index, row ->
            val user = row.optJSONObject("userInfo")
            val album = row.optJSONObject("albumInfo")
            when {
                user != null -> {
                    val brief = anchorBrief(user, row.optJSONObject("statCountInfo"), index)
                    if (brief == null) null else RankEntry(index + 1, anchor = brief)
                }
                album != null -> {
                    val brief = rankAlbum(album, row.optJSONObject("statCountInfo"))
                    if (brief == null) null else RankEntry(index + 1, album = brief)
                }
                else -> null
            }
        }
        return RankPage(clusterCode, categoryCode, data.text("title"), entries)
    }

    private fun rankAlbum(info: JSONObject, stat: JSONObject?): AlbumBrief? {
        val id = info.optLong("id")
        val title = info.text("title")
        if (id <= 0 || title.isBlank()) return null
        return album(
            id = id,
            title = title,
            rawCover = info.text("cover"),
            plays = stat?.optLong("playCount")?.takeIf { it > 0 },
            tracks = stat?.optInt("trackCount")?.takeIf { it > 0 },
            paid = info.optInt("albumPayType", -1).let { if (it < 0) null else it == 1 },
            subtitle = info.text("customTitle").ifBlank { info.text("recommendReason") },
        )
    }

    private fun anchorBrief(user: JSONObject, stat: JSONObject?, index: Int): AnchorBrief? {
        val uid = user.optLong("id")
        val name = user.text("nickname")
        if (uid <= 0 || name.isBlank()) return null
        return AnchorBrief(
            uid = uid,
            name = name,
            avatar = cover(user.text("logo")),
            fans = stat?.optLong("followerCount")?.takeIf { it > 0 },
            albumCount = stat?.optInt("albumCount")?.takeIf { it > 0 },
            trackCount = stat?.optLong("trackCount")?.takeIf { it > 0 },
            verified = user.optInt("verifyStatus", 0) > 0,
            bio = user.text("personDescribe").ifBlank { user.text("ptitle") },
        ).takeIf { index >= 0 }
    }

    fun anchorProfile(json: JSONObject): AnchorProfile? {
        val data = json.optJSONObject("data") ?: return null
        val uid = data.optLong("uid")
        val name = data.text("nickName")
        if (uid <= 0 || name.isBlank()) return null
        return AnchorProfile(
            uid = uid,
            name = name,
            avatar = cover(data.text("cover")),
            background = cover(data.text("background")),
            bio = data.text("personalSignature").ifBlank { data.text("personalDescription") },
            fans = data.optLong("fansCount").takeIf { it > 0 },
            albumCount = data.optInt("albumsCount").takeIf { it > 0 },
            tracks = data.optLong("tracksCount").takeIf { it > 0 },
        )
    }

    fun anchorAlbums(json: JSONObject): AnchorAlbums {
        val page = json.optJSONObject("data")?.optJSONObject("pubPageInfo") ?: return AnchorAlbums(0, emptyList())
        val albums = page.optJSONArray("pubInfoList").objects().mapNotNull { row ->
            val id = row.optLong("id")
            val title = row.text("title")
            if (id <= 0 || title.isBlank()) return@mapNotNull null
            album(
                id = id,
                title = title,
                rawCover = row.text("coverPath"),
                anchorId = row.optLong("anchorUid"),
                anchorName = row.text("anchorNickName"),
                plays = row.optLong("playCount").takeIf { it > 0 },
                tracks = row.optInt("trackCount").takeIf { it > 0 },
                paid = if (row.has("isPaid")) row.optBoolean("isPaid") else null,
                subtitle = row.text("subTitle"),
            )
        }
        return AnchorAlbums(page.optInt("totalCount"), albums)
    }

    fun radioHome(json: JSONObject): RadioHome {
        val data = json.optJSONObject("data") ?: return RadioHome()
        var categories: List<RadioFilter> = emptyList()
        var locations: List<RadioFilter> = emptyList()
        val modules = data.optJSONArray("modules").objects().mapNotNull { module ->
            module.optJSONArray("categories")?.let { array ->
                categories = array.objects().mapNotNull { row ->
                    val id = row.optLong("id")
                    val name = row.text("name")
                    if (name.isBlank()) null else RadioFilter(id, name)
                }
            }
            module.optJSONArray("locations")?.let { array ->
                locations = array.objects().mapNotNull { row ->
                    val name = row.text("name")
                    if (name.isBlank()) null else RadioFilter(row.optLong("id"), name)
                }
            }
            val stations = module.optJSONArray("radios").objects().mapNotNull(::station)
            if (stations.isEmpty()) null else RadioModule(module.text("type"), module.text("name"), stations)
        }
        return RadioHome(data.text("location"), modules, categories, locations)
    }

    private fun station(row: JSONObject): RadioStation? {
        val id = row.optLong("id")
        val name = row.text("name")
        if (id <= 0 || name.isBlank()) return null
        val play = row.optJSONObject("playUrl")
        val stream = listOf("aac64", "ts64", "aac24", "ts24")
            .map { play?.text(it).orEmpty() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return RadioStation(
            id = id,
            name = name,
            cover = cover(row.text("coverLarge").ifBlank { row.text("coverSmall") }),
            programName = row.text("programName"),
            start = row.text("start"),
            end = row.text("end"),
            category = row.text("categoryName"),
            playCount = row.optLong("playCount").takeIf { it > 0 },
            streamUrl = if (stream.isBlank()) "" else cover(stream),
        )
    }

    fun liveRoom(json: JSONObject): LiveRoom? {
        val data = json.optJSONObject("data") ?: return null
        val record = data.optJSONObject("recordInfo") ?: return null
        val roomId = record.optLong("roomId")
        val liveId = record.optLong("id")
        val name = record.text("name")
        if (roomId <= 0 || liveId <= 0 || name.isBlank()) return null
        val user = data.optJSONObject("userInfo")
        return LiveRoom(
            roomId = roomId,
            liveId = liveId,
            name = name,
            anchor = user?.text("nickname").orEmpty(),
            anchorUid = user?.optLong("uid") ?: 0,
            cover = cover(record.text("coverMiddle")),
            online = record.optInt("onlineCount"),
            alive = record.optBoolean("alive"),
            category = "",
        )
    }

    /** Stream address for a live room; `liveId` comes from the room detail. */
    fun liveStream(json: JSONObject): String {
        val data = json.optJSONObject("data") ?: return ""
        val ordered = listOf("tsUrls", "tsUrls2", "aacUrls", "flvUrls2", "flvUrls")
        for (key in ordered) {
            val value = data.optJSONArray(key)?.optString(0).orEmpty()
            if (value.isNotBlank()) return cover(value)
        }
        return ""
    }

    fun albumDetail(json: JSONObject): AlbumDetail? {
        val data = json.optJSONObject("data") ?: return null
        val info = data.optJSONObject("mainInfo") ?: return null
        val id = data.optLong("albumId").takeIf { it > 0 } ?: info.optLong("id")
        val title = info.text("albumTitle")
        if (id <= 0 || title.isBlank()) return null
        val anchor = data.optJSONObject("anchorInfo")
        val tracks = data.optJSONObject("tracksInfo")
        return AlbumDetail(
            albumId = id,
            title = title,
            cover = cover(info.text("cover")),
            anchorId = anchor?.optLong("anchorId") ?: 0,
            anchorName = anchor?.text("anchorName").orEmpty(),
            anchorFans = anchor?.optLong("anchorFansCount")?.takeIf { it > 0 },
            playCount = info.optLong("playCount").takeIf { it > 0 },
            trackCount = tracks?.optInt("trackTotalCount")?.takeIf { it > 0 },
            paid = if (info.has("isPaid")) info.optBoolean("isPaid") else null,
            finished = if (info.has("isFinished")) info.optBoolean("isFinished") else null,
            summary = info.text("shortIntro").ifBlank { info.text("richIntro") },
            updateDate = info.text("updateDate"),
        )
    }
}
