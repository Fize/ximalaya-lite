package com.shixi.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shixi.protocol.AlbumBrief
import com.shixi.protocol.CategoryNode
import com.shixi.protocol.LiveRoom
import com.shixi.protocol.RadioStation

private val discoverTabs = listOf("推荐", "分类", "广播", "榜单", "主播")

@Composable
fun DiscoverScreen(
    repo: DiscoveryRepository,
    onSearch: () -> Unit,
    onAlbum: (AlbumBrief) -> Unit,
    onCategory: (String) -> Unit,
    onAnchor: (Long) -> Unit,
    onLiveRoom: (LiveRoom) -> Unit,
    onPlayRadio: (RadioStation) -> Unit,
    playingRadioId: Long? = null,
    version: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Each tab keeps its own scroll position and transient form state.
    val tabStates = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        PageHeading("发现", onSearch = onSearch)
        PrimaryTabRow(
            selectedTabIndex = tab,
            divider = {},
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            discoverTabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            tabStates.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> DiscoverRecommend(repo, onAlbum, onCategory, version)
                    1 -> DiscoverCategory(repo) { node: CategoryNode -> onCategory(node.code) }
                    2 -> DiscoverBroadcast(repo, onPlayRadio, onLiveRoom, playingRadioId)
                    3 -> DiscoverRank(repo, onAlbum, onAnchor)
                    4 -> DiscoverAnchor(repo, onAnchor)
                }
            }
        }
    }
}
