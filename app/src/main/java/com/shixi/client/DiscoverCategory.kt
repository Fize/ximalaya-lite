package com.shixi.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixi.protocol.AlbumBrief
import com.shixi.protocol.CategoryGroup
import com.shixi.protocol.CategoryNode
import com.shixi.protocol.CategoryPage
import com.shixi.protocol.FilterDimension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DiscoverChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        border = null,
        modifier = Modifier.heightIn(min = 36.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceContainer,
            labelColor = colors.onSurface,
            selectedContainerColor = colors.primaryContainer,
            selectedLabelColor = colors.primary,
        ),
    )
}

@Composable
fun AlbumListRow(album: AlbumBrief, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看专辑 ${album.title}", onClick = onClick)
            .heightIn(min = 88.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArtwork(album.cover, Modifier.size(64.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (album.anchorName.isNotBlank()) add(album.anchorName)
                album.playCount?.let { add("${formatCount(it)}播放") }
                album.trackCount?.let { add("$it 集") }
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ---- category tab ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverCategory(repo: DiscoveryRepository, onCategory: (CategoryNode) -> Unit) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<CategoryGroup>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }

    fun load() {
        val token = ++generation
        loading = groups == null
        error = ""
        scope.launch {
            try {
                val value = repo.categoryGroups()
                if (token == generation) groups = value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) error = discoveryMessage(failure)
            } finally {
                if (token == generation) loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (groups == null) load() }

    val current = groups
    when {
        current == null && loading -> Box(Modifier.fillMaxSize()) { SkeletonRows(6) }
        current == null -> EmptyBlock("分类加载失败", error.ifBlank { "请检查网络后重试" }, actionLabel = "重试") { load() }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (error.isNotBlank()) item { DiscoverErrorBanner(error) { load() } }
            current.forEach { group ->
                item(key = "group-${group.title}") {
                    DiscoverGroupHeading(group.title)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.categories.forEach { category ->
                            DiscoverChip(category.title, false) { onCategory(category) }
                        }
                    }
                }
            }
        }
    }
}

// ---- category result page ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverCategoryPage(
    repo: DiscoveryRepository,
    code: String,
    onAlbum: (AlbumBrief) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var meta by remember(code) { mutableStateOf<CategoryPage?>(null) }
    var albums by remember(code) { mutableStateOf<List<AlbumBrief>>(emptyList()) }
    var subCode by remember(code) { mutableStateOf<String?>(null) }
    var sort by remember(code) { mutableIntStateOf(0) }
    var metas by remember(code) { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var nextPage by remember(code) { mutableIntStateOf(2) }
    var more by remember(code) { mutableStateOf(false) }
    var loading by remember(code) { mutableStateOf(true) }
    var appending by remember(code) { mutableStateOf(false) }
    var error by remember(code) { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var generation by remember(code) { mutableIntStateOf(0) }

    fun apply(reset: Boolean) {
        val token = ++generation
        val page = if (reset) 1 else nextPage
        error = ""
        if (reset) {
            loading = albums.isEmpty()
        } else {
            appending = true
        }
        scope.launch {
            try {
                val rows = repo.categoryAlbums(code, subCode, sort, metas.toList(), page)
                if (token != generation) return@launch
                val before = albums.size
                albums = if (reset) rows else (albums + rows).distinctBy { it.albumId }
                nextPage = page + 1
                // Stop when the server keeps returning the same rows instead of looping forever.
                more = rows.size >= 20 && (reset || albums.size > before)
                if (reset) listState.scrollToItem(0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) error = discoveryMessage(failure)
            } finally {
                if (token == generation) {
                    loading = false
                    appending = false
                }
            }
        }
    }

    var metaAttempt by remember(code) { mutableIntStateOf(0) }
    LaunchedEffect(code, metaAttempt) {
        try {
            val page = repo.categoryPage(code)
            meta = page
            if (page.albums.isNotEmpty()) {
                // queryCategoryPage returns 30 rows; the paged endpoint uses 20/page.
                // Showing the first 20 keeps page 2 (items 21-40) contiguous.
                albums = page.albums.take(20)
                nextPage = 2
                more = page.albums.size > 20
            }
            loading = false
            if (page.albums.isEmpty()) apply(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = discoveryMessage(failure)
            loading = false
        }
    }

    LaunchedEffect(listState, albums.size, more, appending, loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (more && error.isBlank() && !appending && !loading && albums.isNotEmpty() && last >= albums.size - 4) apply(false)
            }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolButton(AppIcon.Back, "返回", onClick = onBack)
            Text(
                meta?.title?.takeIf { it.isNotBlank() } ?: "分类",
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { showFilter = true }, enabled = meta != null) { Text("筛选") }
        }

        val subCategories = meta?.subCategories.orEmpty()
        if (subCategories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                item(key = "all") { DiscoverChip("全部", subCode == null) { if (subCode != null) { subCode = null; apply(true) } } }
                items(subCategories, key = { it.code }) { sub ->
                    DiscoverChip(sub.title, subCode == sub.code) {
                        if (subCode != sub.code) { subCode = sub.code; apply(true) }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "综合", 1 to "最近更新", 2 to "最多播放").forEach { (value, label) ->
                TextButton(onClick = { if (sort != value) { sort = value; apply(true) } }) {
                    Text(
                        label,
                        color = if (sort == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (metas.isNotEmpty()) {
                TextButton(onClick = { metas = emptyMap(); apply(true) }) { Text("清除筛选") }
            }
        }

        when {
            loading && albums.isEmpty() -> SkeletonRows(5)
            albums.isEmpty() -> EmptyBlock("该分类暂无专辑", "换个分类或筛选条件看看", actionLabel = "重试") { metaAttempt++; apply(true) }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (error.isNotBlank()) item { DiscoverErrorBanner(error) { apply(true) } }
                items(albums, key = { it.albumId }) { album -> AlbumListRow(album) { onAlbum(album) } }
                item(key = "footer") {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        when {
                            appending -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            !more && albums.isNotEmpty() -> Text("已显示全部内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            error.isNotBlank() -> TextButton(onClick = { apply(false) }) { Text("加载失败，点击重试") }
                        }
                    }
                }
            }
        }
    }

    if (showFilter) {
        val dimensions = meta?.filters.orEmpty()
        val pick = remember(metas, dimensions) {
            mutableStateMapOf<Long, Long>().apply { metas.forEach { (key, value) -> put(key, value) } }
        }
        ModalBottomSheet(onDismissRequest = { showFilter = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("筛选", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    dimensions.forEach { dimension -> FilterGroup(dimension, pick) }
                    Spacer(Modifier.height(16.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { pick.clear() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重置") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            metas = pick.toMap()
                            showFilter = false
                            apply(true)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("应用") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(dimension: FilterDimension, pick: MutableMap<Long, Long>) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(dimension.title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            dimension.values.forEach { value ->
                val selected = pick[dimension.id] == value.id
                DiscoverChip(value.title, selected) {
                    if (selected) pick.remove(dimension.id) else pick[dimension.id] = value.id
                }
            }
        }
    }
}
