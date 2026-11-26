package com.shixi.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shixi.protocol.LiveRoom
import com.shixi.protocol.RadioHome
import com.shixi.protocol.RadioStation
import kotlinx.coroutines.CancellationException

@Composable
fun DiscoverBroadcast(
    repo: DiscoveryRepository,
    onPlayRadio: (RadioStation) -> Unit,
    onLiveRoom: (LiveRoom) -> Unit,
    playingRadioId: Long? = null,
) {
    var radio by remember { mutableStateOf<RadioHome?>(null) }
    var rooms by remember { mutableStateOf<List<LiveRoom>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(retry) {
        loading = true
        error = ""
        var firstFailure: Throwable? = null
        try {
            radio = repo.radioHome()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            firstFailure = failure
        }
        try {
            rooms = repo.liveRooms()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (firstFailure == null) firstFailure = failure
        }
        if (radio == null && rooms.isEmpty()) error = discoveryMessage(firstFailure ?: Exception())
        loading = false
    }

    val home = radio
    if (home == null && rooms.isEmpty()) {
        when {
            loading -> Box(Modifier.fillMaxSize()) { SkeletonRows(5) }
            else -> EmptyBlock("广播加载失败", error.ifBlank { "请检查网络后重试" }, actionLabel = "重试") { retry++ }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (error.isNotBlank()) item(key = "broadcast-error") { DiscoverErrorBanner(error) { retry++ } }
        home?.modules?.forEach { module ->
            item(key = "radio-${module.type}-${module.title}") {
                val suffix = if (module.type == "LOCAL" && home.location.isNotBlank()) " · ${home.location}" else ""
                DiscoverSectionHeader(module.title + suffix)
            }
            item(key = "radio-${module.type}-${module.title}-rail") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(module.stations, key = { it.id }) { station ->
                        RadioCard(station, Modifier.width(DiscoverRailWidth), playing = playingRadioId == station.id) {
                            onPlayRadio(station)
                        }
                    }
                }
            }
        }
        if (rooms.isNotEmpty()) {
            item(key = "live-header") { DiscoverSectionHeader("正在直播") }
            items(rooms.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { room ->
                        LiveCard(room, Modifier.weight(1f)) { onLiveRoom(room) }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        } else if (!loading) {
            item(key = "live-empty") {
                Text(
                    "当前没有正在直播的主播",
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
