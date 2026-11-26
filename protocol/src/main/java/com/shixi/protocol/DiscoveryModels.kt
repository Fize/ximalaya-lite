package com.shixi.protocol

/**
 * Discovery models mirror the verified public endpoints documented in
 * docs/design/discover-redesign/api-spec.md. Fields stay nullable when the
 * server value or its meaning has not been verified; callers must not invent
 * defaults. Nothing here carries credentials.
 */

data class AlbumBrief(
    val albumId: Long,
    val title: String,
    val cover: String = "",
    val anchorId: Long = 0L,
    val anchorName: String = "",
    val playCount: Long? = null,
    val trackCount: Int? = null,
    val paid: Boolean? = null,
    val vipFree: Boolean? = null,
    /** Only set when the endpoint's flag meaning is verified (category albumFinishTag == 2). */
    val finished: Boolean? = null,
    val subtitle: String = "",
) {
    val valid: Boolean get() = albumId > 0 && title.isNotBlank()
}

data class RecommendSection(
    val title: String,
    val categoryId: Int,
    val categoryCode: String,
    val albums: List<AlbumBrief>,
    val hotWords: List<String> = emptyList(),
    val rankTitle: String = "",
)

data class RecommendFeed(
    val guessYouLike: List<AlbumBrief> = emptyList(),
    val sections: List<RecommendSection> = emptyList(),
)

/** Fallback feed parsed from the mobile discovery API (anonymous responses). */
data class MobileRecommend(
    val albums: List<AlbumBrief> = emptyList(),
    val categorySections: List<RecommendSection> = emptyList(),
    val liveRooms: List<LiveRoom> = emptyList(),
)

data class CategoryNode(val id: Int, val title: String, val code: String)

data class CategoryGroup(val title: String, val categories: List<CategoryNode>)

data class SubCategory(val id: Long, val code: String, val title: String)

data class FilterValue(val id: Long, val title: String)

data class FilterDimension(val id: Long, val title: String, val values: List<FilterValue>)

data class CategoryPage(
    val categoryId: Int,
    val title: String,
    val subCategories: List<SubCategory> = emptyList(),
    val filters: List<FilterDimension> = emptyList(),
    val albums: List<AlbumBrief> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
)

data class RankGroup(val code: String, val title: String)

data class RankCategory(val title: String, val code: String, val rankId: Long)

data class RankEntry(
    val position: Int,
    val album: AlbumBrief? = null,
    val anchor: AnchorBrief? = null,
)

data class RankPage(
    val clusterCode: String,
    val categoryCode: String,
    val title: String,
    val entries: List<RankEntry> = emptyList(),
)

data class AnchorBrief(
    val uid: Long,
    val name: String,
    val avatar: String = "",
    val fans: Long? = null,
    val albumCount: Int? = null,
    val trackCount: Long? = null,
    val verified: Boolean = false,
    val bio: String = "",
) {
    val valid: Boolean get() = uid > 0 && name.isNotBlank()
}

data class AnchorProfile(
    val uid: Long,
    val name: String,
    val avatar: String = "",
    val background: String = "",
    val bio: String = "",
    val fans: Long? = null,
    val albumCount: Int? = null,
    val tracks: Long? = null,
)

data class AnchorAlbums(val total: Int, val albums: List<AlbumBrief>)

data class AlbumDetail(
    val albumId: Long,
    val title: String,
    val cover: String = "",
    val anchorId: Long = 0L,
    val anchorName: String = "",
    val anchorFans: Long? = null,
    val playCount: Long? = null,
    val trackCount: Int? = null,
    val paid: Boolean? = null,
    val finished: Boolean? = null,
    val summary: String = "",
    val updateDate: String = "",
)

data class RadioStation(
    val id: Long,
    val name: String,
    val cover: String = "",
    val programName: String = "",
    val start: String = "",
    val end: String = "",
    val category: String = "",
    val playCount: Long? = null,
    /** Verified HLS address from the home page (`playUrl.aac64`), already https-upgraded. */
    val streamUrl: String = "",
)

data class RadioModule(val type: String, val title: String, val stations: List<RadioStation>)

data class RadioFilter(val id: Long, val name: String)

data class RadioHome(
    val location: String = "",
    val modules: List<RadioModule> = emptyList(),
    val categories: List<RadioFilter> = emptyList(),
    val locations: List<RadioFilter> = emptyList(),
)

data class LiveRoom(
    val roomId: Long,
    val liveId: Long,
    val name: String,
    val anchor: String = "",
    val anchorUid: Long = 0L,
    val cover: String = "",
    val online: Int = 0,
    val alive: Boolean = false,
    val category: String = "",
) {
    val valid: Boolean get() = roomId > 0 && liveId > 0 && name.isNotBlank()
}

class DiscoveryException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
