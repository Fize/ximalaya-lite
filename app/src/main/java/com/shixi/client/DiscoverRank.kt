package com.shixi.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shixi.protocol.RankCategory
import com.shixi.protocol.RankEntry
import com.shixi.protocol.RankGroup
import com.shixi.protocol.RankPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DiscoverRank(repo: DiscoveryRepository, onAlbum: (com.shixi.protocol.AlbumBrief) -> Unit, onAnchor: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<RankGroup>>(emptyList()) }
    var cluster by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf<List<RankCategory>>(emptyList()) }
    var category by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<RankPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var retry by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(retry) {
        try {
            val loaded = repo.rankGroups()
            groups = loaded
            if (cluster == null) cluster = loaded.firstOrNull()?.code
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = discoveryMessage(failure)
            loading = false
        }
    }

    LaunchedEffect(cluster, retry) {
        val active = cluster ?: return@LaunchedEffect
        categories = emptyList()
        category = null
        page = null
        loading = true
        error = ""
        try {
            val loaded = repo.rankCategories(active)
            categories = loaded
            category = loaded.firstOrNull()?.code
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = discoveryMessage(failure)
            loading = false
        }
    }

    LaunchedEffect(cluster, category, retry) {
        val activeCluster = cluster ?: return@LaunchedEffect
        val activeCategory = category ?: return@LaunchedEffect
        loading = true
        error = ""
        try {
            page = repo.rankPage(activeCluster, activeCategory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = discoveryMessage(failure)
        } finally {
            loading = false
        }
    }

    if (groups.isEmpty() && loading) {
        Box(Modifier.fillMaxSize()) { SkeletonRows(6) }
        return
    }
    if (groups.isEmpty()) {
        EmptyBlock("榜单加载失败", error.ifBlank { "请检查网络后重试" }, actionLabel = "重试") {
            scope.launch {
                error = ""
                loading = true
                runCatching { repo.rankGroups() }.onSuccess { groups = it; cluster = it.firstOrNull()?.code }
                    .onFailure { error = discoveryMessage(it) }
                loading = false
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "clusters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(groups, key = { it.code }) { group ->
                    DiscoverChip(group.title, cluster == group.code) {
                        if (cluster != group.code) cluster = group.code
                    }
                }
            }
        }
        item(key = "categories") {
            if (categories.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(categories, key = { "${it.code}-${it.rankId}" }) { item ->
                        DiscoverChip(item.title, category == item.code) {
                            if (category != item.code) category = item.code
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) item(key = "error") { DiscoverErrorBanner(error) { retry++ } }
        val current = page
        if (current == null) {
            if (loading) item(key = "loading") { SkeletonRows(5) }
            else item(key = "empty") { EmptyBlock("该榜单暂无内容", "换个类目看看") }
        } else {
            item(key = "title") {
                Text(
                    current.title,
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(current.entries, key = { "${current.clusterCode}-${current.categoryCode}-${it.position}" }) { entry ->
                RankEntryRow(entry, onAlbum, onAnchor)
            }
        }
    }
}

@Composable
private fun RankEntryRow(entry: RankEntry, onAlbum: (com.shixi.protocol.AlbumBrief) -> Unit, onAnchor: (Long) -> Unit) {
    val album = entry.album
    val anchor = entry.anchor
    when {
        album != null -> RankRow(entry.position, album) { onAlbum(album) }
        anchor != null -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.position.toString(),
                Modifier.widthIn(min = 32.dp),
                style = if (entry.position <= 3) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (entry.position <= 3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.weight(1f)) { AnchorRow(anchor) { onAnchor(anchor.uid) } }
        }
        else -> Unit
    }
}

@Composable
fun DiscoverAnchor(repo: DiscoveryRepository, onAnchor: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<RankCategory>>(emptyList()) }
    var category by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<RankEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var retry by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(retry) {
        try {
            val loaded = repo.rankCategories("anchorInfluence")
            categories = loaded
            category = loaded.firstOrNull()?.code
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = discoveryMessage(failure)
            loading = false
        }
    }

    LaunchedEffect(category, retry) {
        val active = category ?: return@LaunchedEffect
        loading = true
        error = ""
        try {
            entries = repo.rankPage("anchorInfluence", active).entries
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = discoveryMessage(failure)
        } finally {
            loading = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (categories.isNotEmpty()) {
            item(key = "anchor-categories") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(categories, key = { it.code }) { item ->
                        DiscoverChip(item.title, category == item.code) {
                            if (category != item.code) category = item.code
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) item(key = "anchor-error") { DiscoverErrorBanner(error) { retry++ } }
        when {
            loading && entries.isEmpty() -> item(key = "anchor-loading") { SkeletonRows(6) }
            entries.isEmpty() -> item(key = "anchor-empty") {
                EmptyBlock("暂无主播推荐", "稍后再试", actionLabel = "重试") {
                    scope.launch {
                        runCatching { repo.rankPage("anchorInfluence", category.orEmpty()) }
                            .onSuccess { entries = it.entries }
                            .onFailure { error = discoveryMessage(it) }
                    }
                }
            }
            else -> items(entries, key = { "anchor-${it.position}" }) { entry ->
                val anchor = entry.anchor
                if (anchor != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.position.toString(),
                            Modifier.widthIn(min = 32.dp),
                            style = if (entry.position <= 3) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            color = if (entry.position <= 3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(Modifier.weight(1f)) { AnchorRow(anchor) { onAnchor(anchor.uid) } }
                    }
                }
            }
        }
    }
}
