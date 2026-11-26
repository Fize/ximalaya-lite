package com.shixi.client

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixi.protocol.AlbumBrief
import com.shixi.protocol.AnchorBrief
import com.shixi.protocol.LiveRoom
import com.shixi.protocol.RadioStation
import java.util.Locale

/** User-facing message for discovery failures; never exposes raw responses. */
fun discoveryMessage(failure: Throwable): String = when (failure) {
    is com.shixi.protocol.DiscoveryException -> failure.message ?: "内容服务暂时不可用"
    is java.net.SocketTimeoutException -> "连接超时，请重试"
    is java.io.IOException -> "连接失败，请检查网络后重试"
    else -> "加载失败，请重试"
}

/** 1234 → 1234, 12345 → 1.2万, 123456789 → 1.2亿 */
fun formatCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.CHINA, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.CHINA, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

private fun albumMeta(album: AlbumBrief): String = buildList {
    if (album.anchorName.isNotBlank()) add(album.anchorName)
    album.playCount?.let { add("${formatCount(it)}播放") }
    album.trackCount?.let { add("${it}集") }
}.joinToString(" · ")

@Composable
private fun AlbumBadges(album: AlbumBrief) {
    val badges = buildList {
        if (album.vipFree == true) add("VIP")
        if (album.paid == true) add("付费")
        if (album.finished == true) add("完结")
    }
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.forEach { label ->
            val colors = MaterialTheme.colorScheme
            val fill = label == "VIP"
            Surface(
                color = if (fill) colors.primaryContainer else colors.surface,
                contentColor = if (fill) colors.onPrimaryContainer else colors.onSurfaceVariant,
                shape = RoundedCornerShape(4.dp),
                border = if (fill) null else androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
            ) {
                Text(label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun DiscoverAlbumCard(album: AlbumBrief, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "查看专辑 ${album.title}", onClick = onClick)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CoverArtwork(album.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = albumMeta(album)
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AlbumBadges(album)
    }
}

@Composable
fun DiscoverSectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DiscoverGroupHeading(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
fun RankRow(position: Int, album: AlbumBrief, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "查看专辑 ${album.title}", onClick = onClick)
            .heightIn(min = 88.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            position.toString(),
            Modifier.widthIn(min = 32.dp),
            style = if (position <= 3) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (position <= 3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
}

@Composable
fun AnchorRow(anchor: AnchorBrief, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "查看主播 ${anchor.name}", onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArtwork(anchor.avatar, Modifier.size(56.dp), CircleShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(anchor.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (anchor.verified) {
                    Text("  ✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            val meta = buildList {
                if (anchor.bio.isNotBlank()) add(anchor.bio)
                anchor.fans?.let { add("${formatCount(it)}粉丝") }
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.inverseSurface, contentColor = colors.inverseOnSurface, shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(colors.inversePrimary))
            Text("LIVE", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun LiveCard(room: LiveRoom, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "进入直播间 ${room.name}", onClick = onClick)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            CoverArtwork(room.cover, Modifier.fillMaxWidth().aspectRatio(1f))
            LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Text(room.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = buildList {
            if (room.anchor.isNotBlank()) add(room.anchor)
            if (room.online > 0) add("${formatCount(room.online.toLong())}人在听")
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun RadioCard(station: RadioStation, modifier: Modifier = Modifier, playing: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "播放电台 ${station.name}", onClick = onClick)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoverArtwork(station.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(station.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val program = listOfNotNull(
            station.programName.takeIf { it.isNotBlank() },
            station.start.takeIf { it.isNotBlank() }?.let { start -> if (station.end.isNotBlank()) "$start-${station.end}" else start },
        ).joinToString(" · ")
        val meta = when {
            playing -> "正在播放"
            program.isNotBlank() -> program
            else -> station.category
        }
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer),
    )
}

@Composable
fun SkeletonAlbumGrid(columns: Int = 2, rows: Int = 2, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Column(modifier.fillMaxWidth().alpha(alpha), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(columns) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f))
                        SkeletonBlock(Modifier.fillMaxWidth().height(14.dp))
                        SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonRows(rows: Int = 4, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeletonRows")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "skeletonRowsAlpha",
    )
    Column(modifier.fillMaxWidth().alpha(alpha), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.size(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(14.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(10.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyBlock(title: String, body: String = "", modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppSymbol(AppIcon.Info, null, Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotBlank()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
fun DiscoverErrorBanner(message: String, onRetry: (() -> Unit)?) {
    InlineNotice(message, onAction = onRetry)
}

/** Two-column grid rendered without a nested scroll container. */
@Composable
fun AlbumPairRow(left: AlbumBrief?, right: AlbumBrief?, onAlbum: (AlbumBrief) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(left, right).forEach { album ->
            if (album == null) {
                Spacer(Modifier.weight(1f))
            } else {
                DiscoverAlbumCard(album, Modifier.weight(1f)) { onAlbum(album) }
            }
        }
    }
}

/** Live/radio cards reuse the same 132dp horizontal rhythm as the wireframe. */
val DiscoverRailWidth = 132.dp
