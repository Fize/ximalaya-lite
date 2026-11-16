package com.shixi.client

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shixi.protocol.EpisodeComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EpisodeCommentsSheet(trackId: Long, signedIn: Boolean, api: AccountContentApi, actions: SocialActionsApi, countUpdated: (Long) -> Unit, login: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    var rows by remember(trackId) { mutableStateOf<List<EpisodeComment>>(emptyList()) }
    var page by remember(trackId) { mutableIntStateOf(0) }
    var more by remember(trackId) { mutableStateOf(true) }
    var loading by remember(trackId) { mutableStateOf(false) }
    var error by remember(trackId) { mutableStateOf(false) }
    var retryNext by remember(trackId) { mutableStateOf(false) }
    var sending by remember(trackId) { mutableStateOf(false) }
    var draft by remember(trackId) { mutableStateOf("") }
    var notice by remember(trackId) { mutableStateOf("") }
    fun load(next: Boolean = false) {
        if (loading) return
        loading = true; error = false; retryNext = next
        val target = if (next) page + 1 else 1
        scope.launch {
            try { val result = withContext(Dispatchers.IO) { api.episodeComments(trackId, target) }; rows = (if (next) rows + result.rows else result.rows).distinctBy { it.id }; page = target; more = result.hasMore; if (target == 1) result.totalCount?.let(countUpdated) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
            finally { loading = false }
        }
    }
    LaunchedEffect(trackId) { load() }
    LaunchedEffect(scroll, rows.size, loading, more, error) {
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (rows.isNotEmpty() && more && !loading && !error && last >= rows.size - 2) load(true)
        }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal = 20.dp)) {
        Text("评论", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp).semantics { heading() })
        LazyColumn(state = scroll, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.id }) { comment ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Surface(Modifier.size(36.dp).clip(CircleShape), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        if (comment.avatar.startsWith("https://")) AsyncImage(model = comment.avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Box(contentAlignment = Alignment.Center) { AppSymbol(AppIcon.Person) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(comment.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        if (comment.createdAt > 0) Text(remember(comment.createdAt) { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(comment.createdAt)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { when { loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp)); error -> TextButton(onClick = { load(retryNext) }) { Text("加载失败，点击重试") }; rows.isEmpty() -> Text("还没有评论", Modifier.padding(16.dp)); !more -> Text("已显示全部评论", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) } }
        }
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        if (notice.isNotBlank()) Text(notice, color = if (notice == "评论已提交") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= 500) draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("写评论") }, shape = RoundedCornerShape(20.dp), maxLines = 3, enabled = !sending)
            TextButton(enabled = !sending && draft.isNotBlank(), onClick = {
                if (!signedIn) { login(); return@TextButton }
                val lease = actions.lease() ?: run { notice = "请先登录"; return@TextButton }
                val text = draft.trim(); sending = true; notice = ""
                scope.launch { try { withContext(Dispatchers.IO) { actions.comment(lease, trackId, text) }; draft = ""; notice = "评论已提交"; load() }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "发送未确认，请检查评论后再试" } finally { sending = false } }
            }) { Text(if (sending) "发送中…" else "发送") }
        }
        Spacer(Modifier.height(12.dp))
    }
}
