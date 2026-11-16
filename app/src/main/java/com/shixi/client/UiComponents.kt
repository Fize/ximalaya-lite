package com.shixi.client

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.shixi.protocol.ContentApi
import com.shixi.protocol.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local vectors avoid font glyph substitution and an additional icon runtime dependency. */
enum class AppIcon(val path: String) {
    Home("M12 3 2 12h3v9h6v-6h2v6h6v-9h3z"),
    Discover("M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20m0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16m5 3-3 7-7 3 3-7z"),
    Library("M3 3h5v18H3zm7 0h5v18h-5zm7 1 4-1 3 17-4 1z"),
    Search("M10 2a8 8 0 1 0 0 16 8 8 0 0 0 0-16m0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12m6 11 7 7-1.5 1.5-7-7z"),
    Settings("M10 2h4l.7 3 2 .9 2.8-.9 2 3.5-2.1 2.2v2.6l2.1 2.2-2 3.5-2.8-.9-2 .9L14 22h-4l-.7-3-2-.9-2.8.9-2-3.5 2.1-2.2v-2.6L2.5 8.5 4.5 5l2.8.9 2-.9zM12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8"),
    Play("M8 4v16l12-8z"), Pause("M5 4h5v16H5zm9 0h5v16h-5z"),
    Previous("M4 5h3v14H4zm15 0L8 12l11 7z"), Next("M17 5h3v14h-3zM5 5v14l11-7z"),
    Back("m12 3 1.4 1.4L6.8 11H22v2H6.8l6.6 6.6L12 21 3 12z"),
    Down("m4 7 8 8 8-8 1.4 1.4L12 18 2.6 8.4z"),
    Queue("M3 4h18v2H3zm0 6h12v2H3zm0 6h12v2H3zm15-7v12l6-6z"),
    Timer("M8 1h8v2H8zm3 4h2v8h5v2h-7zM12 4a9 9 0 1 0 0 18 9 9 0 0 0 0-18m0 2a7 7 0 1 1 0 14 7 7 0 0 1 0-14"),
    Speed("M12 3a10 10 0 0 0-9 15l2-1a8 8 0 1 1 14 0l2 1a10 10 0 0 0-9-15m5 4-7 5a3 3 0 1 0 4 4z"),
    Forward("M13 3v4a8 8 0 1 0 7 8h-2a6 6 0 1 1-5-6v4l8-5z"),
    Replay("M11 3 3 8l8 5V9a6 6 0 1 1-5 6H4a8 8 0 1 0 7-8z"),
    Person("M12 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10m0 2a3 3 0 1 1 0 6 3 3 0 0 1 0-6m0 10c-5 0-9 3-9 7v1h18v-1c0-4-4-7-9-7m0 2c3 0 6 2 7 4H5c1-2 4-4 7-4"),
    Headphones("M12 3a9 9 0 0 0-9 9v8h6v-8H5a7 7 0 0 1 14 0h-4v8h6v-8a9 9 0 0 0-9-9"),
    Info("M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20m0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16m-1 3h2v2h-2zm0 4h2v6h-2z"),
    More("M4 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4m8 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4m8 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4"),
    Skip("M4 4 12 12 4 20 5.5 21.5 15 12 5.5 2.5zm7 0 8 8-8 8 1.5 1.5L22 12 12.5 2.5z"),
    Comment("M4 3h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H9l-5 3v-3H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2m0 2v12h2v1.5L8.5 17H20V5z"),
    Heart("M12 21s-9-5.7-9-11a5 5 0 0 1 9-3 5 5 0 0 1 9 3c0 5.3-9 11-9 11m0-2.4c2.3-1.6 7-5.3 7-8.6a3 3 0 0 0-5.4-1.8L12 10l-1.6-1.8A3 3 0 0 0 5 10c0 3.3 4.7 7 7 8.6"),
    HeartFilled("M12 21s-9-5.7-9-11a5 5 0 0 1 9-3 5 5 0 0 1 9 3c0 5.3-9 11-9 11"),
    Clean("M4 5h16v2H4zm4-3h8v2H8zm-2 6h12l-1 14H7zm3 2v9h2v-9zm4 0v9h2v-9z");
    val vector: ImageVector by lazy { ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd).build() }
}

@Composable fun AppSymbol(icon: AppIcon, description: String? = null, modifier: Modifier = Modifier) {
    Icon(icon.vector, description, modifier.size(20.dp))
}
@Composable fun SymbolButton(icon: AppIcon, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { AppSymbol(icon, label) }
}
@Composable fun CompactAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(color = if (enabled) colors.primary else colors.surfaceContainerHighest, contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant, shape = RoundedCornerShape(24.dp)) {
            Box(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}
@Composable fun CoverArtwork(url: String?, modifier: Modifier = Modifier) {
    Surface(modifier.clip(RoundedCornerShape(12.dp)), color = MaterialTheme.colorScheme.surfaceContainer) {
        SubcomposeAsyncImage(model = url?.takeIf { it.isNotBlank() }, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppSymbol(AppIcon.Headphones) } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppSymbol(AppIcon.Headphones) } })
    }
}
@Composable fun AlbumCard(item: Item, supporting: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        CoverArtwork(item.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun PageHeading(title: String, onSettings: (() -> Unit)? = null, onSearch: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        onSearch?.let { SymbolButton(AppIcon.Search, "搜索专辑", onClick = it) }
        onSettings?.let { SymbolButton(AppIcon.Settings, "设置", onClick = it) }
    }
}
@Composable fun AppNavigation(tab: Int, rail: Boolean, onSelect: (Int) -> Unit) {
    val titles = listOf("收听", "发现", "我的")
    val icons = listOf(AppIcon.Headphones, AppIcon.Discover, AppIcon.Library)
    val c = MaterialTheme.colorScheme
    if (rail) NavigationRail(Modifier.width(96.dp).fillMaxHeight(), containerColor = c.surface) {
        Spacer(Modifier.height(24.dp))
        titles.forEachIndexed { i, title -> NavigationRailItem(selected = tab == i, onClick = { onSelect(i) }, icon = { AppSymbol(icons[i]) }, label = { Text(title) }, colors = NavigationRailItemDefaults.colors(indicatorColor = c.surface, selectedIconColor = c.primary, selectedTextColor = c.primary)) }
    } else NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
        titles.forEachIndexed { i, title -> NavigationBarItem(selected = tab == i, onClick = { onSelect(i) }, icon = { AppSymbol(icons[i]) }, label = { Text(title, style = MaterialTheme.typography.labelSmall) }, colors = NavigationBarItemDefaults.colors(indicatorColor = c.surface, selectedIconColor = c.primary, selectedTextColor = c.primary, unselectedIconColor = c.onSurfaceVariant, unselectedTextColor = c.onSurfaceVariant)) }
    }
}
@Composable fun SectionHeading(title: String, onAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        onAll?.let { TextButton(onClick = it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("全部") } }
    }
}
@Composable fun AlbumLine(item: Item, supporting: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverArtwork(item.cover, Modifier.size(64.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        AppSymbol(AppIcon.Next)
    }
}
@Composable fun ListeningRow(title: String, subtitle: String, pending: Boolean = false, playing: Boolean = false, onClick: () -> Unit, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled = !pending, role = Role.Button, onClick = onClick).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (pending) "正在加载…" else subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pending) Box(Modifier.size(48.dp).semantics { contentDescription = "正在加载 $title" }, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        else SymbolButton(if (playing) AppIcon.Pause else AppIcon.Play, "${if (playing) "暂停" else "播放"} $title", onClick = onClick)
        onMore?.let { SymbolButton(AppIcon.More, "$title 更多操作", onClick = it) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
val LocalFeedbackHost = staticCompositionLocalOf<SnackbarHostState> { error("Feedback host missing") }
/** No layout footprint. Leaving the owning page cancels its message and retry action. */
@Composable fun FloatingNotice(text: String, action: String = "重试", onAction: (() -> Unit)? = null) {
    val host = LocalFeedbackHost.current
    val latestAction by rememberUpdatedState(onAction)
    LaunchedEffect(text, action) {
        host.currentSnackbarData?.dismiss()
        if (host.showSnackbar(text, actionLabel = action.takeIf { onAction != null },
                withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) latestAction?.invoke()
    }
}
@Composable fun InlineNotice(text: String, action: String = "重试", onAction: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            onAction?.let { TextButton(onClick = it) { Text(action) } }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RefreshContent(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction("刷新列表") { if (!refreshing) onRefresh(); true }) }) { content() }
}
@Composable fun HomeScreen(local: ListeningData, current: Item?, position: Float, duration: Float, playbackState: String,
    onPlayer: () -> Unit, onHistory: (HistoryRow) -> Unit, onAlbum: (Item) -> Unit, onLibrary: () -> Unit,
    onDiscover: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit,
    playing: Boolean = false, pendingId: Long? = null, onContinue: () -> Unit = onPlayer,
    subscriptions: List<Item> = emptyList(), refreshing: Boolean = false, onRefresh: () -> Unit = {}, onHistoryMore: (HistoryRow) -> Unit = {}, onAllHistory: () -> Unit = onLibrary) {
    val compact = LocalConfiguration.current.screenWidthDp < 360
    RefreshContent(refreshing, onRefresh, Modifier.fillMaxSize()) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageHeading("收听", onSearch = onSearch) }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("继续收听", Modifier.semantics { heading() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current == null) {
                        Text("还没有收听记录", style = MaterialTheme.typography.titleMedium)
                        CompactAction("去发现", onClick = onDiscover)
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CoverArtwork(current.cover, Modifier.size(if (compact) 88.dp else 112.dp).clickable(onClick = onPlayer))
                        Column(Modifier.weight(1f)) {
                            Text(current.title, Modifier.clickable(onClick = onPlayer), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(if (duration > position) "剩余 ${(duration - position).toLong() / 60000} 分钟" else "听到 ${position.toLong() / 60000} 分钟", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            CompactAction(if (pendingId == current.id) "正在加载…" else if (playing) "暂停" else "继续播放", pendingId != current.id, onContinue)
                        }
                    }
                }
            }
        }
        item { SectionHeading("最近收听", onAllHistory) }
        if (local.history.isEmpty()) item { Text("听过的节目会出现在这里", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item { Column { local.history.take(3).forEach { row -> ListeningRow(row.title, if (row.completed) "已听完" else "听到 ${row.positionMs / 60000} 分钟", pendingId == row.id, playing && current?.id == row.id, { onHistory(row) }, { onHistoryMore(row) }) } } }
        item { SectionHeading("我的订阅", onLibrary) }
        val albums = (subscriptions + local.follows).distinctBy { it.id }
        if (albums.isEmpty()) item { Text("订阅喜欢的专辑，随时回来听", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else items(albums.take(4), key = { "follow-${it.id}" }) { item -> AlbumLine(item, if (subscriptions.any { it.id == item.id }) null else "仅本机") { onAlbum(item) } }
    } }
}
@Composable fun DiscoveryScreen(store: DiscoveryStore, onSearch: () -> Unit, onAlbum: (Item) -> Unit) {
    var topic by rememberSaveable { mutableStateOf(DiscoveryStore.topics.first()) }
    val cached by store.state.collectAsState()
    val current = cached[topic] ?: DiscoveryPage()
    val scrolls = DiscoveryStore.topics.map { rememberLazyGridState() }
    val scroll = scrolls[DiscoveryStore.topics.indexOf(topic)]
    LaunchedEffect(topic) { store.ensure(topic) }
    val latest by rememberUpdatedState(current)
    LaunchedEffect(topic, scroll) {
        snapshotFlow {
            val layout = scroll.layoutInfo
            scroll.isScrollInProgress && latest.loaded && latest.more && !latest.busy &&
                latest.error.isEmpty() && layout.visibleItemsInfo.lastOrNull()?.index?.let {
                    it >= layout.totalItemsCount - 3
                } == true
        }.collect { nearEnd -> if (nearEnd) store.next(topic) }
    }
    if (current.error.isNotEmpty() && current.rows.isNotEmpty()) FloatingNotice("更新失败，请稍后重试") { if (current.failedPage > 1) store.next(topic) else store.refresh(topic) }
    RefreshContent(current.refreshing, { store.refresh(topic) }, Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), state = scroll, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Column {
                PageHeading("发现", onSearch = onSearch)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiscoveryStore.topics.forEach { value -> FilterChip(selected = topic == value, onClick = { topic = value }, label = { Text(value) }, border = null, colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary)) }
                }
                Spacer(Modifier.height(20.dp)); SectionHeading("发现专辑")
                if (current.busy && !current.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                if (current.error.isNotEmpty() && current.rows.isEmpty()) InlineNotice("加载失败，${if (current.rows.isEmpty()) "请重试" else "已保留上次内容"}") { if (current.failedPage > 1) store.next(topic) else store.refresh(topic) }
                else if (!current.busy && current.loaded && current.rows.isEmpty()) Text("暂无专辑，换个分类看看", style = MaterialTheme.typography.bodyMedium)
            } }
            items(current.rows, key = { it.id }) { item -> AlbumCard(item) { onAlbum(item) } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    when {
                        current.busy && current.loaded && !current.refreshing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        current.error.isNotEmpty() && current.failedPage > 1 -> TextButton(onClick = { store.next(topic) }) { Text("加载失败，点击重试") }
                        current.loaded && !current.more && current.rows.isNotEmpty() -> Text("已显示全部内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
enum class SyncViewState { SIGNED_OUT, UNCHECKED, SYNCING, SYNCED, PENDING, ERROR, EXPIRED }
/** Adapt the existing CloudSync message contract centrally; unknown outcomes stay visible. */
fun syncViewState(cloud: CloudState): SyncViewState = when {
    cloud.busy -> SyncViewState.SYNCING
    cloud.message.startsWith("登录已失效") -> SyncViewState.EXPIRED
    cloud.message == "登录后可同步账号历史和订阅" -> SyncViewState.SIGNED_OUT
    cloud.message == "服务器核对完成" && cloud.pending == 0 -> SyncViewState.SYNCED
    cloud.message.startsWith("账号记录已读取") || cloud.message == "正在核对服务器…" -> SyncViewState.UNCHECKED
    Regex("^[0-9]+项(待同步|尚未由服务器确认).*").matches(cloud.message) -> SyncViewState.PENDING
    else -> SyncViewState.ERROR
}
fun syncViewText(cloud: CloudState): String = when (syncViewState(cloud)) {
    SyncViewState.SIGNED_OUT -> "登录后同步订阅与历史"
    SyncViewState.UNCHECKED -> "尚未同步"
    SyncViewState.SYNCING -> "正在同步…"
    SyncViewState.SYNCED -> "已同步"
    SyncViewState.PENDING -> "${cloud.pending} 项待同步"
    SyncViewState.ERROR -> "同步失败，请稍后重试"
    SyncViewState.EXPIRED -> "登录已失效，请重新登录"
}
@Composable fun LibraryScreen(local: ListeningData, cloud: CloudState, cloudVisible: Boolean,
    onSettings: () -> Unit, onAccount: () -> Unit, onQueue: () -> Unit, onSync: () -> Unit,
    onAlbum: (Item) -> Unit, onToggle: (CloudSubscriptionRow) -> Unit, onHistory: (HistoryRow) -> Unit,
    onCloudHistory: (CloudHistoryRow) -> Unit, pendingId: Long? = null, currentId: Long? = null, playing: Boolean = false,
    onDiscover: () -> Unit = {}, initialFilter: String = "订阅", onFilterChanged: (String) -> Unit = {}, onCurrentPlayback: () -> Unit = {}) {
    val filter = initialFilter
    val scroll = rememberLazyListState()
    RefreshContent(cloudVisible && cloud.busy, onSync, Modifier.fillMaxSize()) {
    LazyColumn(state = scroll, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageHeading("我的", onSettings); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("订阅", "历史").forEach { value -> FilterChip(filter == value, onClick = { onFilterChanged(value) }, label = { Text(value) }, border = null, colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary)) }
        } }
        if (filter == "订阅") {
            val subs = if (cloudVisible) cloud.subscriptions.filter { it.subscribed || !it.confirmed } else emptyList()
            if (subs.isEmpty() && local.follows.isEmpty()) item { Column(Modifier.padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("还没有订阅", style = MaterialTheme.typography.titleMedium); CompactAction("去发现", onClick = onDiscover); if (!cloudVisible) TextButton(onClick = onAccount) { Text("登录查看订阅") } } }
            items(subs, key = { "cloud-${it.id}" }) { row -> AlbumLine(row.item(), if (!row.confirmed) "待同步" else null) { onAlbum(row.item()) } }
            val localOnly = local.follows.filter { item -> subs.none { it.id == item.id } }
            if (localOnly.isNotEmpty()) item { Spacer(Modifier.height(16.dp)); Text("本机收藏", style = MaterialTheme.typography.titleMedium) }
            items(localOnly, key = { "local-${it.id}" }) { item -> AlbumLine(item, "仅本机") { onAlbum(item) } }
        } else {
            val remote = if (cloudVisible) cloud.history else emptyList()
            // Display one row per episode, prefer local progress if it was heard more recently.
            val localRows = local.history.filter { row -> remote.none { it.id == row.id && maxOf(it.localAt, it.serverAt) > row.updatedAt } }
            val remoteRows = remote.filter { row -> localRows.none { it.id == row.id } }
            if (localRows.isEmpty() && remoteRows.isEmpty()) item { Column(Modifier.padding(vertical = 40.dp)) { Text("还没有收听记录"); TextButton(onClick = onDiscover) { Text("去发现") } } }
            items((localRows.map { it.id to it.updatedAt } + remoteRows.map { it.id to maxOf(it.localAt, it.serverAt) }).sortedByDescending { it.second }, key = { "history-${it.first}" }) { (id, _) ->
                val row = localRows.firstOrNull { it.id == id }
                val remoteRow = remoteRows.firstOrNull { it.id == id }
                if (row != null) ListeningRow(row.title, if (row.completed) "已听完" else "听到 ${row.positionMs / 60000} 分钟", pendingId == id, playing && currentId == id, { onHistory(row) })
                else if (remoteRow != null) ListeningRow(remoteRow.title, "听到 ${remoteRow.positionMs / 60000} 分钟", pendingId == id, playing && currentId == id, { if (currentId == id) onCurrentPlayback() else onCloudHistory(remoteRow) })
            }
        }
    } }
}
fun speedText(speed: Float): String = java.math.BigDecimal.valueOf(speed.toDouble()).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "×"

@Composable fun PlaybackSpeedPanel(speed: Float, enabled: Boolean, onSpeed: (Float) -> Unit) {
    fun change(value: Float) { if (enabled) onSpeed((kotlin.math.round(value * 100) / 100).coerceIn(0.5f, 3f)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { change(speed - 0.1f) }, enabled = enabled && speed > 0.5f,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "降低速度 0.1 倍" }) { Text("−") }
            Text(speedText(speed), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OutlinedButton(onClick = { change(speed + 0.1f) }, enabled = enabled && speed < 3f,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "提高速度 0.1 倍" }) { Text("＋") }
        }
        Slider(value = speed.coerceIn(0.5f, 3f), onValueChange = { change(kotlin.math.round(it * 10) / 10) },
            enabled = enabled, valueRange = 0.5f..3f, steps = 24,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "自定义播放速度"; stateDescription = speedText(speed) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0.5×", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("3×", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("快捷倍速", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                FilterChip(selected = kotlin.math.abs(speed - value) < 0.001f, enabled = enabled,
                    onClick = { change(value) }, label = { Text(speedText(value)) },
                    modifier = Modifier.semantics { contentDescription = "快捷倍速 " + speedText(value) })
            }
        }
        if (!enabled) Text("正在连接播放器…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SettingRow(title: String, value: String = "", icon: AppIcon, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSymbol(icon)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 132.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) Text("›", modifier = Modifier.clearAndSetSemantics {}, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(content = content) }
    }
}
@Composable fun SettingsScreen(account: String, speed: Float, ready: Boolean, cacheMessage: String, clearingCache: Boolean, version: String,
    onBack: () -> Unit, onAccount: () -> Unit, onSpeed: () -> Unit, onSleep: () -> Unit, onClearCache: () -> Unit,
    cloud: CloudState = CloudState(), cloudVisible: Boolean = false, onSync: () -> Unit = {}, sleep: String = "关闭") {
    if (cacheMessage.isNotEmpty()) FloatingNotice(cacheMessage)
    val accountLabel = accountStatusText(account)
    RefreshContent(cloudVisible && cloud.busy, onSync, Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { SymbolButton(AppIcon.Back, "返回", onClick = onBack); Text("设置", style = MaterialTheme.typography.headlineLarge) } }
            item { SettingsGroup("账号") {
                SettingRow("账号与同步", accountLabel, AppIcon.Person, onClick = onAccount)
                if (cloudVisible) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow("同步收听记录", syncViewText(cloud), AppIcon.Library, enabled = !cloud.busy, onClick = onSync)
                }
            } }
            item { SettingsGroup("播放") {
                SettingRow("播放速度", speedText(speed), AppIcon.Speed, enabled = ready, onClick = onSpeed)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow("定时停止", sleep, AppIcon.Timer, enabled = ready, onClick = onSleep)
            } }
            item { SettingsGroup("存储与关于") {
                SettingRow("清理封面缓存", if (clearingCache) "清理中…" else "", AppIcon.Clean, enabled = !clearingCache, onClick = onClearCache)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow("版本", version.substringBefore('-'), AppIcon.Info)
            }
            }
        }
    }
}
