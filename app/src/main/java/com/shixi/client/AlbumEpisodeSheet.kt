package com.shixi.client

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shixi.protocol.Item
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun AlbumEpisodeSheet(rows: List<Item>, currentId: Long?, extras: Bundle,
    more: (Int) -> Unit, retry: () -> Unit, select: (Item) -> Unit) {
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0) + 1)
    val loading = extras.getBoolean("albumLoading")
    val ready = extras.getBoolean("albumReady")
    val generation = extras.getInt("albumGeneration")
    var positioned by remember(currentId, generation) { mutableStateOf(false) }
    val before = extras.getBoolean("albumBefore")
    val after = extras.getBoolean("albumAfter")
    val issue = extras.getString("albumIssue").orEmpty()
    LaunchedEffect(ready) { if (!ready) more(1) }
    LaunchedEffect(currentId, rows.size, ready, loading, generation) {
        if (!positioned && ready && !loading) {
            val index = rows.indexOfFirst { it.id == currentId }
            if (index >= 0) { scroll.scrollToItem(index + 1); positioned = true }
        }
    }
    LaunchedEffect(rows.size, loading, before, after, issue, positioned) {
        snapshotFlow { scroll.firstVisibleItemIndex to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .distinctUntilChanged().collect { (first, last) ->
                if (positioned && !loading && issue.isEmpty()) {
                    if (before && first <= 1) more(-1)
                    else if (after && last >= rows.size - 2) more(1)
                }
            }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("选集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp).semantics { heading() })
        LazyColumn(state = scroll, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "before") { if (loading && before) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp)) }
            items(rows, key = { it.id }) { item ->
                val current = item.id == currentId
                ListItem(headlineContent = { Text(item.title, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = if (current) ({ Text("正在收听") }) else null,
                    modifier = Modifier.semantics { selected = current }.clickable { select(item) })
            }
            item(key = "status") {
                when {
                    loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                    issue.isNotEmpty() -> TextButton(onClick = retry) { Text("加载失败，点击重试") }
                    !after && rows.isNotEmpty() -> Text("已到最后一集", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    rows.isEmpty() -> Text("暂无节目", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
