package com.shixi.client

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EpisodeLikeButton(trackId: Long, signedIn: Boolean, owner: Long, api: SocialActionsApi, modifier: Modifier = Modifier, login: () -> Unit) {
    val scope = rememberCoroutineScope()
    var liked by remember(trackId, owner) { mutableStateOf<Boolean?>(null) }
    var loading by remember(trackId, owner) { mutableStateOf(false) }
    var error by remember(trackId, owner) { mutableStateOf(false) }
    fun refresh() {
        if (loading || !signedIn) return
        val lease = api.lease() ?: return
        if (lease.session.uid != owner) return
        loading = true; error = false
        scope.launch { try { liked = withContext(Dispatchers.IO) { api.likeStatus(lease, trackId) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { liked = null; error = true } finally { loading = false } }
    }
    LaunchedEffect(trackId, owner, signedIn) { if (signedIn) refresh() }
    PlayerAction(if (liked == true) AppIcon.HeartFilled else AppIcon.Heart,
        when { loading -> "请稍候"; error -> "重试点赞"; liked == true -> "已赞"; else -> "点赞" },
        modifier = modifier, enabled = !loading, active = liked == true, onClick = {
        if (!signedIn) { login(); return@PlayerAction }
        val current = liked
        if (current == null) { refresh(); return@PlayerAction }
        val lease = api.lease() ?: return@PlayerAction
        if (lease.session.uid != owner) return@PlayerAction
        loading = true; error = false
        scope.launch { try { withContext(Dispatchers.IO) { api.like(lease, trackId, current != true) }; liked = current != true }
            catch (e: CancellationException) { throw e } catch (_: Exception) { liked = null; error = true } finally { loading = false } }
    })
}
