package com.shixi.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixi.protocol.AlbumBrief
import com.shixi.protocol.AnchorAlbums
import com.shixi.protocol.AnchorProfile
import kotlinx.coroutines.CancellationException

@Composable
fun DiscoverAnchorDetail(
    repo: DiscoveryRepository,
    uid: Long,
    onBack: () -> Unit,
    onAlbum: (AlbumBrief) -> Unit,
) {
    val listState = rememberLazyListState()
    var profile by remember(uid) { mutableStateOf<AnchorProfile?>(null) }
    var albums by remember(uid) { mutableStateOf<AnchorAlbums?>(null) }
    var loading by remember(uid) { mutableStateOf(true) }
    var error by remember(uid) { mutableStateOf("") }
    var retry by remember(uid) { mutableIntStateOf(0) }

    LaunchedEffect(uid, retry) {
        loading = true
        error = ""
        var firstFailure: Throwable? = null
        try {
            profile = repo.anchorProfile(uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            firstFailure = failure
        }
        try {
            albums = repo.anchorAlbums(uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (firstFailure == null) firstFailure = failure
        }
        if (profile == null && albums == null) error = discoveryMessage(firstFailure ?: Exception())
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolButton(AppIcon.Back, "返回", onClick = onBack)
            Text(
                profile?.name?.takeIf { it.isNotBlank() } ?: "主播",
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            loading && profile == null && albums == null -> SkeletonRows(5)
            profile == null && albums == null -> EmptyBlock("主播资料加载失败", error.ifBlank { "请检查网络后重试" }, actionLabel = "重试") { retry++ }
            else -> LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                if (error.isNotBlank()) item(key = "anchor-detail-error") { DiscoverErrorBanner(error) { retry++ } }
                profile?.let { item(key = "anchor-profile") { AnchorHeader(it) } }
                albums?.let { value ->
                    item(key = "anchor-albums-header") {
                        DiscoverSectionHeader("TA 的专辑")
                        Text(
                            "共 ${value.total} 张，展示前 ${value.albums.size} 张",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(value.albums, key = { it.albumId }) { album -> AlbumListRow(album) { onAlbum(album) } }
                }
            }
        }
    }
}

@Composable
private fun AnchorHeader(profile: AnchorProfile) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CoverArtwork(profile.avatar, Modifier.size(88.dp), CircleShape)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    profile.fans?.let { add("${formatCount(it)}粉丝") }
                    profile.albumCount?.let { add("$it 张专辑") }
                    profile.tracks?.let { add("${formatCount(it)}集") }
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (profile.bio.isNotBlank()) {
            Text(profile.bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun DiscoverLiveRoom(
    repo: DiscoveryRepository,
    roomId: Long,
    onBack: () -> Unit,
    playing: Boolean,
    onToggle: (com.shixi.protocol.LiveRoom) -> Unit,
) {
    var room by remember(roomId) { mutableStateOf<com.shixi.protocol.LiveRoom?>(null) }
    var loading by remember(roomId) { mutableStateOf(true) }
    var error by remember(roomId) { mutableStateOf("") }
    var retry by remember(roomId) { mutableIntStateOf(0) }

    LaunchedEffect(roomId, retry) {
        loading = true
        error = ""
        try {
            room = repo.liveRoom(roomId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = discoveryMessage(failure)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolButton(AppIcon.Back, "返回", onClick = onBack)
            Text("直播间", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        }
        val current = room
        when {
            loading && current == null -> SkeletonRows(4)
            current == null -> EmptyBlock("直播间加载失败", error.ifBlank { "请检查网络后重试" }, actionLabel = "重试") { retry++ }
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Box {
                    CoverArtwork(current.cover, Modifier.fillMaxWidth().aspectRatio(1f))
                    LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(current.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val meta = buildList {
                    if (current.anchor.isNotBlank()) add(current.anchor)
                    if (current.online > 0) add("${formatCount(current.online.toLong())}人在听")
                    add(if (current.alive) "正在直播" else "已结束")
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                CompactAction(if (playing) "暂停" else "播放直播", enabled = current.alive) { onToggle(current) }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    DiscoverErrorBanner(error) { retry++ }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
