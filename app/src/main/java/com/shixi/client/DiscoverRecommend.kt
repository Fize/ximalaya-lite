package com.shixi.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shixi.protocol.AlbumBrief
import com.shixi.protocol.RecommendFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DiscoverRecommend(
    repo: DiscoveryRepository,
    onAlbum: (AlbumBrief) -> Unit,
    onCategory: (String) -> Unit,
    version: Int = 0,
) {
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf<RecommendFeed?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }

    fun load(refresh: Boolean) {
        val token = ++generation
        error = ""
        if (refresh) refreshing = true else if (feed == null) loading = true
        scope.launch {
            try {
                val value = repo.recommend(refresh)
                if (token == generation) feed = value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) error = discoveryMessage(failure)
            } finally {
                if (token == generation) {
                    loading = false
                    refreshing = false
                }
            }
        }
    }

    LaunchedEffect(version) { if (version > 0 || feed == null) load(version > 0) }

    RefreshContent(refreshing, { load(true) }, Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error.isNotBlank() && feed != null) {
                item(key = "recommend-error") { DiscoverErrorBanner(error) { load(true) } }
            }
            val current = feed
            if (current == null) {
                if (loading) {
                    item(key = "recommend-skeleton") { SkeletonAlbumGrid(rows = 2) }
                } else {
                    item(key = "recommend-empty") {
                        EmptyBlock(
                            title = "推荐内容加载失败",
                            body = error.ifBlank { "请检查网络后重试" },
                            actionLabel = "重试",
                        ) { load(false) }
                    }
                }
            } else {
                if (current.guessYouLike.isNotEmpty()) {
                    item(key = "guess-header") {
                        DiscoverSectionHeader("猜你喜欢", "换一换") { load(true) }
                    }
                    items(current.guessYouLike.chunked(2)) { pair ->
                        AlbumPairRow(pair.getOrNull(0), pair.getOrNull(1), onAlbum)
                    }
                }
                current.sections.forEach { section ->
                    item(key = "section-${section.categoryCode}-header") {
                        DiscoverSectionHeader(
                            title = section.title,
                            actionLabel = "全部",
                            onAction = section.categoryCode.takeIf { it.isNotBlank() }?.let { code -> { onCategory(code) } },
                        )
                    }
                    item(key = "section-${section.categoryCode}-rail") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(section.albums.take(10), key = { it.albumId }) { album ->
                                DiscoverAlbumCard(album, Modifier.width(DiscoverRailWidth)) { onAlbum(album) }
                            }
                        }
                    }
                }
                if (current.guessYouLike.isEmpty() && current.sections.isEmpty()) {
                    item(key = "recommend-blank") {
                        EmptyBlock("暂无推荐内容", "稍后再试", actionLabel = "刷新") { load(true) }
                    }
                }
            }
        }
    }
}
