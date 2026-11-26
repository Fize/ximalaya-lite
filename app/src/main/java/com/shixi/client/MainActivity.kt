@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shixi.client

import okio.Path.Companion.toPath
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.shixi.protocol.ContentApi
import com.shixi.protocol.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AlbumReturnState(val album: Item?, val rows: ArrayList<Item>, val page: Int,
    val more: Boolean, val index: Int, val offset: Int, val reversed: Boolean) : java.io.Serializable

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>
    override fun onDestroy() { MediaController.releaseFuture(controllerFuture); super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coil3.SingletonImageLoader.setSafe { context -> coil3.ImageLoader.Builder(context)
            .diskCache { coil3.disk.DiskCache.Builder().directory(context.cacheDir.resolve("covers").path.toPath()).maxSizeBytes(50L * 1024 * 1024).build() }.build() }
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        setContent {
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            SideEffect { androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !darkTheme; isAppearanceLightNavigationBars = !darkTheme } }
            MaterialTheme(colorScheme = clientColors(darkTheme), typography = ClientTypography) {
                val wide = LocalConfiguration.current.screenWidthDp >= 600
                val scope = rememberCoroutineScope()
                val focusManager = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val api = remember { ContentApi() }
                val content = remember { AccountContentApi(this@MainActivity) }
                val discovery = remember { DiscoveryRepository.get(this@MainActivity) }
                var query by rememberSaveable { mutableStateOf("") }
                var searchMessage by rememberSaveable { mutableStateOf("输入关键词搜索专辑") }
                var message by rememberSaveable { mutableStateOf("输入关键词搜索专辑") }
                var entries by rememberSaveable { mutableStateOf(arrayListOf<Item>()) }
                var albums by remember { mutableStateOf(true) }
                var loading by remember { mutableStateOf(false) }
                var requestError by remember { mutableStateOf<String?>(null) }
                var requestGeneration by remember { mutableIntStateOf(0) }
                var requestJob by remember { mutableStateOf<Job?>(null) }
                fun cancelRequest() { requestGeneration++; requestJob?.cancel(); requestJob = null; loading = false; requestError = null; if (searchMessage == "正在搜索…") searchMessage = if (entries.isEmpty()) "搜索已取消，点击搜索重试" else "搜索结果" }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var libraryFilter by rememberSaveable { mutableStateOf("订阅") }
                // An older saved task may still point at the removed fourth destination.
                if (tab !in 0..2) tab = 0
                var album by rememberSaveable { mutableStateOf<Item?>(null) }
                val albumReturns = rememberSaveable { hashMapOf<Int, AlbumReturnState>() }
                val knownAlbums = rememberSaveable { hashMapOf<Long, Item>() }
                @Composable fun rememberTabStack(index: Int) = rememberSaveable(saver = Saver(save = { ArrayList(it) }, restore = { saved -> mutableStateListOf<String>().apply { addAll(saved) } })) { mutableStateListOf("root-$index") }
                val stacks = listOf(rememberTabStack(0), rememberTabStack(1), rememberTabStack(2))
                val pageState = rememberSaveableStateHolder()
                val stack = stacks[tab]
                val showPlayer = stack.lastOrNull() == "player-$tab"
                var albumEntries by rememberSaveable { mutableStateOf(arrayListOf<Item>()) }
                val albumsByTab = rememberSaveable(saver = Saver(save = { HashMap(it) }, restore = { saved -> mutableStateMapOf<Int, Item>().apply { putAll(saved) } })) { mutableStateMapOf<Int, Item>() }
                val tracksByTab = rememberSaveable(saver = Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<Int, List<Item>>, HashMap<Int, ArrayList<Item>>>(save = { HashMap(it.mapValues { entry -> ArrayList(entry.value) }) }, restore = { saved -> mutableStateMapOf<Int, List<Item>>().apply { putAll(saved) } })) { mutableStateMapOf<Int, List<Item>>() }
                val categoryByTab = rememberSaveable(saver = Saver(save = { HashMap(it) }, restore = { saved -> mutableStateMapOf<Int, String>().apply { putAll(saved) } })) { mutableStateMapOf<Int, String>() }
                val anchorByTab = rememberSaveable(saver = Saver(save = { HashMap(it) }, restore = { saved -> mutableStateMapOf<Int, Long>().apply { putAll(saved) } })) { mutableStateMapOf<Int, Long>() }
                val liveByTab = rememberSaveable(saver = Saver(save = { HashMap(it) }, restore = { saved -> mutableStateMapOf<Int, Long>().apply { putAll(saved) } })) { mutableStateMapOf<Int, Long>() }
                val preferenceStore = remember { ContentPreferenceStore.get(this@MainActivity) }
                var personalized by remember { mutableStateOf(preferenceStore.personalized) }
                var recommendVersion by remember { mutableIntStateOf(0) }
                var reversed by rememberSaveable { mutableStateOf(false) }
                var searchPage by rememberSaveable { mutableIntStateOf(1) }
                var albumRequestNext by remember { mutableStateOf(false) }
                var trackPage by rememberSaveable { mutableIntStateOf(1) }
                var moreSearch by rememberSaveable { mutableStateOf(false) }
                var moreTracks by rememberSaveable { mutableStateOf(false) }
                val searchScroll = rememberLazyGridState()
                val albumScrolls = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
                var sheet by remember { mutableStateOf<String?>(null) }
                DisposableEffect(this@MainActivity) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && sheet == "skip") sheet = null
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                var sleepLabel by remember { mutableStateOf("关闭") }
                val localStore = remember { ListeningStore.get(this@MainActivity) }
                val local by localStore.state.collectAsState()
                val accountManager = remember { AccountManager.get(this@MainActivity) }
                val accountState by accountManager.state.collectAsState()
                val cloudSync = remember { CloudSync.get(this@MainActivity) }
                val cloudState by cloudSync.state.collectAsState()
                val cloudVisible by rememberUpdatedState(accountState.signedIn && cloudState.owner == "account:${accountState.uid}")
                val effectiveLocal by rememberUpdatedState(if (accountState.signedIn) local.copy(history = if (cloudVisible) (cloudState.history.map { it.history() } + local.accountHistory.filter { it.owner == cloudState.owner }).groupBy { it.id }.values.map { rows -> rows.maxBy { it.updatedAt } }.sortedByDescending { it.updatedAt } else emptyList()) else local)
                DisposableEffect(cloudSync) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_START) cloudSync.resume()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val followed = local.follows
                var cacheMessage by remember { mutableStateOf("") }
                var clearingCache by remember { mutableStateOf(false) }
                val versionName = remember { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }
                fun clearCovers() { if (clearingCache) return; cacheMessage = ""; clearingCache = true; scope.launch { try { withContext(Dispatchers.IO) { val loader = coil3.SingletonImageLoader.get(this@MainActivity); loader.diskCache?.clear(); loader.memoryCache?.clear() }; cacheMessage = "封面缓存已清理" } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { cacheMessage = "清理失败，请重试" } finally { clearingCache = false } } }
                var storageReady by remember { mutableStateOf(false) }
                var connectionFailed by remember { mutableStateOf(false) }
                fun retryStorage() { controllerFuture.addListener({ runCatching { controllerFuture.get().sendCustomCommand(SessionCommand("com.shixi.client.SAVE", Bundle.EMPTY), Bundle.EMPTY) } }, androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity)) }

                val queue = remember { mutableStateListOf<Item>() }
                var currentTrack by remember { mutableStateOf<Item?>(null) }
                var commentCount by remember(currentTrack?.id) { mutableStateOf<Long?>(null) }
                var commentCountRevision by remember(currentTrack?.id) { mutableIntStateOf(0) }
                LaunchedEffect(currentTrack?.id) {
                    val trackId = currentTrack?.id ?: return@LaunchedEffect
                    val revision = commentCountRevision
                    try { val count = withContext(Dispatchers.IO) { content.episodeComments(trackId, 1).totalCount }; if (revision == commentCountRevision) commentCount = count }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { if (revision == commentCountRevision) commentCount = null }
                }
                val playerAlbum = produceState<Item?>(null, currentTrack?.albumId, accountState.generation, accountState.credentialRevision, accountState.signedIn) {
                    value = null
                    val id = currentTrack?.albumId ?: return@produceState
                    repeat(2) { attempt ->
                        value = try { withContext(Dispatchers.IO) { content.albumDetails(id) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                        if (value != null) return@produceState
                        if (attempt == 0) delay(1000)
                    }
                }

                var playbackIntentGeneration by remember { mutableIntStateOf(0) }
                var accessMessage by remember { mutableStateOf<String?>(null) }
                var retryAccess by remember { mutableStateOf<(() -> Unit)?>(null) }
                var guestAccess by remember { mutableStateOf(false) }
                var checkingAccess by remember { mutableStateOf(false) }
                var pendingTrackId by remember { mutableStateOf<Long?>(null) }
                var failedTrackId by remember { mutableStateOf<Long?>(null) }
                var moreTrack by remember { mutableStateOf<Item?>(null) }
                val snackbars = remember { SnackbarHostState() }
                fun cancelPlaybackIntent() { playbackIntentGeneration++; checkingAccess = false; pendingTrackId = null; failedTrackId = null; accessMessage = null; retryAccess = null }
                fun openAccount() { cancelPlaybackIntent(); startActivity(android.content.Intent(this@MainActivity, AccountActivity::class.java)) }
                fun openSettings() { cancelPlaybackIntent(); if (stack.lastOrNull() != "settings-$tab") stack.add("settings-$tab") }
                LaunchedEffect(message) { if (message in setOf("请先选择节目", "暂时无法打开该节目，请重试", "正在准备播放器…", "播放器尚未连接", "定时设置失败")) { snackbars.currentSnackbarData?.dismiss(); snackbars.showSnackbar(message, withDismissAction = true) } }
                fun showTimerFailure(text: String) { scope.launch { snackbars.currentSnackbarData?.dismiss(); snackbars.showSnackbar(text, withDismissAction = true) } }
                var controller by remember { mutableStateOf<MediaController?>(null) }
                var playbackIssue by remember { mutableStateOf("") }
                var playbackState by remember { mutableStateOf("未播放") }
                var position by remember { mutableFloatStateOf(0f) }
                var duration by remember { mutableFloatStateOf(0f) }
                var pendingSeek by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
                var seekGeneration by remember { mutableIntStateOf(0) }
                LaunchedEffect(currentTrack?.id, currentTrack?.albumId) { pendingSeek = null; seekGeneration++ }
                var playbackSpeed by remember { mutableFloatStateOf(1f) }
                DisposableEffect(Unit) {
                    fun syncPlayer() {
                        val player = controller ?: return
                        storageReady = player.sessionExtras.getBoolean("storageReady", false)
                        queue.clear()
                        for (index in 0 until player.mediaItemCount) PlaybackItems.item(player.getMediaItemAt(index))?.let { queue.add(it) }
                        currentTrack = player.currentMediaItem?.let { PlaybackItems.item(it) }
                        playbackIssue = if (player.playerError != null && player.playWhenReady || player.sessionExtras.getString("playbackIssue").orEmpty().isNotBlank()) "暂时无法播放，请重试" else ""
                        playbackState = PlaybackItems.status(player)
                        playbackSpeed = player.playbackParameters.speed
                        position = player.currentPosition.coerceAtLeast(0).toFloat()
                        duration = PlaybackItems.duration(player).toFloat()
                        // Headset/notification resume bypasses startTrack. Once audio runs,
                        // the controller owns the live cursor rather than the paused preview.
                        if (player.isPlaying) pendingSeek = null
                    }
                    val listener = object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) { syncPlayer() }
                    }
                    future.addListener({ if (!isDestroyed) { try {
                        controller = future.get(); controller?.addListener(listener); syncPlayer()
                    } catch (_: Exception) { playbackState = "播放器连接失败"; connectionFailed = true } } }, androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity))
                    onDispose { controller?.removeListener(listener) }
                }
                LaunchedEffect(controller) { while (true) { controller?.let { storageReady = it.sessionExtras.getBoolean("storageReady", false); position = it.currentPosition.coerceAtLeast(0).toFloat(); duration = PlaybackItems.duration(it).toFloat(); playbackState = PlaybackItems.status(it) }; val prefs = getSharedPreferences("sleep", MODE_PRIVATE); val deadline = if (prefs.getInt("boot", -2) == Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)) prefs.getLong("deadline", 0) else 0; sleepLabel = if (deadline == 0L) "关闭" else if (deadline > SystemClock.elapsedRealtime()) "约${(deadline - SystemClock.elapsedRealtime() + 59999) / 60000}分钟" else "即将停止"; delay(500) } }
                fun seekPlayback(target: Long) {
                    val player = controller ?: return
                    val track = currentTrack ?: return
                    if (!storageReady || duration <= 0) return
                    cancelPlaybackIntent()
                    val point = target.coerceIn(0, duration.toLong())
                    val ticket = ++seekGeneration
                    pendingSeek = Triple(track.id, track.albumId, point)
                    position = point.toFloat()
                    val result = player.sendCustomCommand(SessionCommand("com.shixi.client.CURSOR", Bundle.EMPTY), Bundle().apply {
                        putLong("trackId", track.id); putLong("albumId", track.albumId); putLong("positionMs", point)
                    })
                    result.addListener({
                        if (ticket == seekGeneration) {
                            val success = runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
                            if (!success) {
                                pendingSeek = null
                                scope.launch { snackbars.showSnackbar("进度调整失败，请重试", withDismissAction = true) }
                            } else if (player.playWhenReady) pendingSeek = null
                        }
                    }, androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity))
                }
                fun mediaItem(track: Item, url: String): MediaItem = PlaybackItems.mediaItem(track)
                fun run(action: suspend () -> Unit) { cancelRequest(); val generation = requestGeneration; loading = true; requestJob = scope.launch { try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (generation == requestGeneration) { message = com.shixi.protocol.userContentMessage(e); requestError = message } } finally { if (generation == requestGeneration) { loading = false; requestJob = null } } } }
                fun navigateBack() {
                    sheet = null
                    cancelPlaybackIntent()
                    if (stack.size <= 1) return
                    val popped = stack.removeAt(stack.lastIndex)
                    if (popped == "search-$tab") cancelRequest()
                    if (popped == "playing-album-$tab") {
                        cancelRequest()
                        albumReturns.remove(tab)?.let { saved ->
                            album = saved.album; albumEntries = saved.rows; trackPage = saved.page; moreTracks = saved.more; reversed = saved.reversed
                            if (saved.album != null) albumsByTab[tab] = saved.album else albumsByTab.remove(tab)
                            tracksByTab[tab] = saved.rows
                            val scroll = albumScrolls[tab]; scope.launch { scroll.scrollToItem(saved.index, saved.offset) }
                        }
                    } else if (popped == "album-$tab") { cancelRequest(); album = null; albumsByTab.remove(tab); tracksByTab.remove(tab) }
                    else if (popped == "discover-category-$tab") categoryByTab.remove(tab)
                    else if (popped == "discover-anchor-$tab") anchorByTab.remove(tab)
                    else if (popped == "discover-live-$tab") liveByTab.remove(tab)
                }
                fun openSearch() { cancelPlaybackIntent(); if (stack.lastOrNull() != "search-$tab") stack.add("search-$tab") }
                androidx.activity.compose.BackHandler(enabled = stack.size > 1) { navigateBack() }
                fun albumItem(brief: com.shixi.protocol.AlbumBrief): Item = Item(brief.albumId, brief.title, cover = brief.cover)
                fun openDiscoverCategory(code: String) { if (code.isBlank()) return; categoryByTab[tab] = code; if (stack.lastOrNull() != "discover-category-$tab") stack.add("discover-category-$tab") }
                fun openDiscoverAnchor(uid: Long) { if (uid <= 0) return; anchorByTab[tab] = uid; if (stack.lastOrNull() != "discover-anchor-$tab") stack.add("discover-anchor-$tab") }
                fun openDiscoverLive(roomId: Long) { if (roomId <= 0) return; liveByTab[tab] = roomId; if (stack.lastOrNull() != "discover-live-$tab") stack.add("discover-live-$tab") }
                fun playLiveMedia(kind: String, id: Long, extra: Long, title: String, subtitle: String, cover: String) {
                    val player = controller
                    if (player == null || !storageReady) { message = "正在准备播放器…"; return }
                    if (id <= 0 || extra <= 0) { message = "暂时无法打开该直播，请重试"; return }
                    cancelPlaybackIntent()
                    player.setMediaItem(PlaybackItems.live(kind, id, extra, title, subtitle, cover), 0L)
                    player.prepare()
                    player.play()
                }
                fun playRadio(station: com.shixi.protocol.RadioStation) {
                    if (station.streamUrl.isBlank()) { message = "该电台暂不可播放"; return }
                    playLiveMedia("radio", station.id, 64L, station.name, station.programName.ifBlank { "电台" }, station.cover)
                }
                fun openPlayer() {
                    sheet = null
                    if (stack.contains("player-$tab")) { while (stack.lastOrNull() != "player-$tab") navigateBack() }
                    else stack.add("player-$tab")
                }
                fun switchTab(index: Int) { if (index != tab) { cancelPlaybackIntent(); cancelRequest(); album?.let { albumsByTab[tab] = it; tracksByTab[tab] = albumEntries } ?: kotlin.run { albumsByTab.remove(tab); tracksByTab.remove(tab) }; tab = index; album = albumsByTab[index]; albumEntries = ArrayList(tracksByTab[index].orEmpty()); trackPage = albumEntries.lastOrNull()?.page ?: 1; moreTracks = albumEntries.count { it.page == trackPage } == 20 } }
                fun loadSearch(next: Boolean = false) { val requestedQuery = query.trim(); if (!next) { localStore.search(requestedQuery); keyboard?.hide(); focusManager.clearFocus(); scope.launch { searchScroll.scrollToItem(0) }; entries = arrayListOf(); moreSearch = false }; val target = if (next) searchPage + 1 else 1; run { searchMessage = "正在搜索…"; try { val result = withContext(Dispatchers.IO) { api.search(requestedQuery, target) }; entries = ArrayList((if (next) entries + result else result).distinctBy { it.id }); searchPage = target; moreSearch = result.size == 20; albums = true; album = null; searchMessage = if (entries.isEmpty()) "没有找到相关专辑" else "搜索结果" } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { searchMessage = com.shixi.protocol.userContentMessage(error); throw error } } }
                fun openAlbum(item: Item, next: Boolean = false, refresh: Boolean = false) { albumRequestNext = next; knownAlbums[item.id] = item; if (album?.id != item.id) cancelPlaybackIntent(); if (!next && stack.lastOrNull() !in setOf("album-$tab", "playing-album-$tab")) { stack.add("album-$tab"); scope.launch { albumScrolls[tab].scrollToItem(0) }; moreTracks = false; trackPage = 1 }; val target = if (next) trackPage + 1 else 1; run { album = item; if (!next && !refresh) albumEntries = arrayListOf(); val result = withContext(Dispatchers.IO) { content.tracks(item.id, target).map { it.copy(cover = item.cover) } }; albumEntries = ArrayList((if (next) albumEntries + result else result).distinctBy { it.id }); trackPage = target; moreTracks = result.size == 20; message = if (albumEntries.isEmpty()) "暂无节目" else "节目" } }
                fun startTrack(entry: Item, requestedHistory: HistoryRow? = null) {
                    val player = controller
                    if (player == null || !storageReady) { message = "正在准备播放器…"; return }
                    if (entry.id <= 0 || entry.albumId <= 0) { message = "暂时无法打开该节目，请重试"; return }
                    val existing = (0 until player.mediaItemCount).firstOrNull {
                        PlaybackItems.item(player.getMediaItemAt(it))?.let { it.id == entry.id && it.albumId == entry.albumId && it.page == entry.page } == true
                    }
                    val merged = effectiveLocal.history.firstOrNull { it.id == entry.id }
                    val saved = if (merged != null && merged.updatedAt >= (requestedHistory?.updatedAt ?: 0)) merged else requestedHistory
                    val event = player.sessionExtras
                    val ownEvent = event.getString("listeningOwner") == (accountManager.lease()?.owner ?: LOCAL_OWNER) && event.getLong("listeningTrack") == entry.id
                    val latestPlayer = existing == player.currentMediaItemIndex && (player.playWhenReady || ownEvent && event.getLong("listeningAt") > (saved?.updatedAt ?: 0))
                    val current = player.currentMediaItem?.let(PlaybackItems::item)
                    val sameTrack = current?.id == entry.id && current.albumId == entry.albumId
                    val adjusted = if (requestedHistory == null && sameTrack) pendingSeek?.takeIf { it.first == entry.id && it.second == entry.albumId }?.third
                        ?: if (player.sessionExtras.getLong("manualSeekTrack") == entry.id && player.sessionExtras.getLong("manualSeekAlbum") == entry.albumId && player.sessionExtras.getLong("manualSeekAt") >= (saved?.updatedAt ?: 0)) player.currentPosition else null else null
                    val resume = adjusted ?: if (latestPlayer) player.currentPosition else if (saved?.completed == true) 0L else if (requestedHistory != null) saved?.resumeMs ?: 0 else if (existing == player.currentMediaItemIndex && player.playWhenReady) player.currentPosition else saved?.resumeMs ?: 0
                    if (existing != null && player.playbackState != Player.STATE_IDLE) player.seekTo(existing, resume)
                    else player.setMediaItems(listOf(PlaybackItems.mediaItem(entry)), 0, resume)
                    player.prepare(); player.play()
                    pendingSeek = null
                    message = entry.title
                }
                LaunchedEffect(accountState) {
                    val downgrade: (Item) -> Item = { if (it.access == com.shixi.protocol.ListeningAccess.AUTHORIZED) it.copy(free = false, access = com.shixi.protocol.ListeningAccess.UNKNOWN) else it }
                    albumEntries = ArrayList(albumEntries.map(downgrade))
                    tracksByTab.keys.toList().forEach { tracksByTab[it] = tracksByTab[it].orEmpty().map(downgrade) }
                }
                fun authorizeTrack(entry: Item, action: (Item) -> Unit) {
                    if (checkingAccess && pendingTrackId == entry.id) return
                    cancelPlaybackIntent(); failedTrackId = entry.id; accessMessage = null; retryAccess = null
                    if (entry.access in setOf(com.shixi.protocol.ListeningAccess.TRIAL, com.shixi.protocol.ListeningAccess.UNAVAILABLE)) {
                        guestAccess = false; retryAccess = null; accessMessage = "本集暂不可收听"
                        return
                    }
                    val member = entry.access in setOf(com.shixi.protocol.ListeningAccess.VIP_REQUIRED, com.shixi.protocol.ListeningAccess.PAID_REQUIRED, com.shixi.protocol.ListeningAccess.AUTHORIZED)
                    if (member && !accountState.signedIn) {
                        guestAccess = true; retryAccess = null
                        accessMessage = "登录后收听本集"
                        return
                    }
                    val ticket = ++playbackIntentGeneration
                    val destination = stack.toList(); val destinationTab = tab
                    checkingAccess = true; pendingTrackId = entry.id
                    scope.launch {
                        try {
                            // Only the explicitly selected episode waits for local recovery;
                            // browsing stays available and the service still owns queue restoration.
                            val ready = withTimeoutOrNull(10_000) { snapshotFlow { storageReady && controller != null }.first { it } }
                            if (ticket != playbackIntentGeneration || tab != destinationTab || (stack.toList() != destination && stack.toList() != destination + "player-$destinationTab")) return@launch
                            if (ready != true) throw java.io.IOException("播放器暂未就绪")
                            val identity = accountManager.contentIdentity()
                            val checked = withContext(Dispatchers.IO) { val fresh = if (entry.access == com.shixi.protocol.ListeningAccess.UNKNOWN) content.tracks(entry.albumId, entry.page).firstOrNull { it.id == entry.id } ?: content.locateTrack(entry.albumId, entry.id) else entry; content.resolve(fresh); fresh }
                            if (ticket != playbackIntentGeneration || tab != destinationTab || (stack.toList() != destination && stack.toList() != destination + "player-$destinationTab")) return@launch
                            if (accountManager.contentIdentity() != identity) throw com.shixi.protocol.ContentAccessException(com.shixi.protocol.ListeningAccess.UNKNOWN, "账号会话已改变，请重新选择节目", sessionChanged = true)
                            val verified = checked.copy(access = if (checked.free) com.shixi.protocol.ListeningAccess.FREE else com.shixi.protocol.ListeningAccess.AUTHORIZED)
                            val mark: (Item) -> Item = { if (it.id == verified.id && it.albumId == verified.albumId) it.copy(free = verified.free, access = verified.access) else it }
                            albumEntries = ArrayList(albumEntries.map(mark))
                            tracksByTab.keys.toList().forEach { tracksByTab[it] = tracksByTab[it].orEmpty().map(mark) }
                            accessMessage = null; failedTrackId = null; action(verified)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (ticket == playbackIntentGeneration && tab == destinationTab && (stack.toList() == destination || stack.toList() == destination + "player-$destinationTab")) {
                                guestAccess = failure is com.shixi.protocol.ContentAccessException && (failure.serviceCode in setOf(50, 401, 403) || !accountManager.state.value.signedIn && failure.access in setOf(com.shixi.protocol.ListeningAccess.VIP_REQUIRED, com.shixi.protocol.ListeningAccess.PAID_REQUIRED))
                                accessMessage = if (!storageReady || controller == null) "播放器暂未就绪，请重试" else if (guestAccess) { if (accountState.signedIn) "登录状态需要更新，请重试" else "登录后收听本集" } else if (failure is com.shixi.protocol.ContentAccessException && failure.access in setOf(com.shixi.protocol.ListeningAccess.VIP_REQUIRED, com.shixi.protocol.ListeningAccess.PAID_REQUIRED, com.shixi.protocol.ListeningAccess.TRIAL, com.shixi.protocol.ListeningAccess.UNAVAILABLE)) "本集暂不可收听" else com.shixi.protocol.userContentMessage(failure)
                                retryAccess = if (guestAccess || failure is com.shixi.protocol.ContentAccessException && failure.access in setOf(com.shixi.protocol.ListeningAccess.UNAVAILABLE, com.shixi.protocol.ListeningAccess.TRIAL)) null else ({ authorizeTrack(entry, action) })
                            }
                        } finally { if (ticket == playbackIntentGeneration) { checkingAccess = false; pendingTrackId = null } }
                    }
                }
                fun playTrack(entry: Item, requestedHistory: HistoryRow? = null) {
                    val player = controller
                    val current = player?.currentMediaItem?.let(PlaybackItems::item)
                    // The original player reuses the loaded source for "continue listening".
                    // Only a fresh selection of another episode needs a new grant.
                    if (player != null && player.playbackState != Player.STATE_IDLE && player.playerError == null &&
                        current?.id == entry.id && current.albumId == entry.albumId) {
                        startTrack(entry, requestedHistory); return
                    }
                    authorizeTrack(entry) { verified -> startTrack(verified, requestedHistory) }
                }
                @Composable fun AccessFeedback() {
                    accessMessage?.let { text -> FloatingNotice(text, if (guestAccess) { if (accountState.signedIn) "账号" else "登录" } else "重试", if (guestAccess) ({ openAccount() }) else retryAccess) }
                }
                fun togglePlayback() {
                    cancelPlaybackIntent()
                    val player = controller ?: return
                    if (player.playWhenReady) player.pause() else if (currentTrack != null) {
                        if (player.playbackState == Player.STATE_READY && player.playerError == null) startTrack(currentTrack!!)
                        else playTrack(currentTrack!!)
                    } else message = "请先选择节目"
                }
                fun selectTrack(item: Item) { if (currentTrack?.id == item.id && controller?.playWhenReady == true) togglePlayback() else playTrack(item) }
                fun playCloudHistory(row: CloudHistoryRow) {
                    val lease = accountManager.lease() ?: return
                    if (lease.owner != row.owner || pendingTrackId == row.id) return
                    cancelPlaybackIntent(); val intent = playbackIntentGeneration
                    pendingTrackId = row.id; accessMessage = null; failedTrackId = row.id
                    val destination = stack.toList(); val destinationTab = tab
                    scope.launch {
                        try {
                            val track = withContext(Dispatchers.IO) { content.locateTrack(row.albumId, row.id) }
                            if (intent == playbackIntentGeneration && accountManager.current(lease) && tab == destinationTab && stack.toList() == destination) playTrack(track, row.history())
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (intent == playbackIntentGeneration && tab == destinationTab && stack.toList() == destination) {
                                guestAccess = failure is com.shixi.protocol.ContentAccessException && failure.serviceCode in setOf(50, 401, 403)
                                accessMessage = com.shixi.protocol.userContentMessage(failure); retryAccess = if (guestAccess || failure is com.shixi.protocol.ContentAccessException && failure.access == com.shixi.protocol.ListeningAccess.UNAVAILABLE) null else ({ playCloudHistory(row) })
                            }
                        } finally { if (intent == playbackIntentGeneration) pendingTrackId = null }
                    }
                }
                fun refreshLibrary() {
                    if (cloudVisible) {
                        cloudSync.sync()
                        scope.launch {
                            cloudSync.awaitIdle()
                            if (syncViewState(cloudSync.state.value) == SyncViewState.ERROR) snackbars.showSnackbar("同步失败，请稍后重试")
                        }
                    } else scope.launch { snackbars.showSnackbar("已更新") }
                }
                fun retryPlayback() {
                    val player = controller
                    if (player == null || player.currentMediaItem == null) { message = "请先选择节目"; return }
                    player.currentMediaItem?.let(PlaybackItems::item)?.let { playTrack(it) }
                }

                fun albumCommand(action: String, direction: Int = 1) {
                    controller?.sendCustomCommand(SessionCommand("com.shixi.client.ALBUM", Bundle.EMPTY), Bundle().apply { putString("action", action); putInt("direction", direction) })
                }
                var albumExtras by remember { mutableStateOf(Bundle.EMPTY) }
                LaunchedEffect(controller) { while (true) { albumExtras = controller?.sessionExtras ?: Bundle.EMPTY; delay(300) } }
                if (sheet == "comments") currentTrack?.let { track ->
                    val social = remember { SocialActionsApi(this@MainActivity) }
                    ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        EpisodeCommentsSheet(track, accountState.signedIn, content, social, { count -> if (currentTrack?.id == track.id) { commentCountRevision++; commentCount = count } }) { sheet = null; openAccount() }
                    }
                }
                if (sheet == "queue") ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    AlbumEpisodeSheet(queue.toList(), currentTrack?.id, albumExtras,
                        { direction -> albumCommand("load", direction) }, { albumCommand("retry") },
                        { item -> sheet = null; playTrack(item) })
                }
                if (sheet == "episodes") ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    val episodeScroll = rememberLazyListState()
                    LaunchedEffect(episodeScroll, albumEntries.size, moreTracks, loading, requestError) {
                        snapshotFlow { episodeScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
                            if (last >= albumEntries.size - 2 && albumEntries.isNotEmpty() && moreTracks && !loading && requestError == null) album?.let { openAlbum(it, true) }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("选集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp).semantics { heading() })
                        LazyColumn(state = episodeScroll, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                            items(if (reversed) albumEntries.reversed() else albumEntries, key = { it.id }) { item ->
                                ListItem(headlineContent = { Text(item.title) }, modifier = Modifier.clickable { sheet = null; selectTrack(item) })
                            }
                            item { when { loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp)); requestError != null -> TextButton(onClick = { album?.let { openAlbum(it, true) } }) { Text("加载失败，点击重试") }; !moreTracks -> Text("已到最后一集", modifier = Modifier.padding(16.dp)) } }
                        }
                    }
                }
                if (sheet != null && sheet != "queue" && sheet != "episodes" && sheet != "comments") ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxWidth().then(if (sheet == "sleep") Modifier.imePadding() else Modifier).heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                        Text(when (sheet) { "speed" -> "播放速度"; "sleep" -> "定时停止"; "skip" -> "略过片头片尾"; "more" -> "节目操作"; "album-actions" -> "专辑操作"; "episodes" -> "选集"; else -> "选集" }, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        when (sheet) {
                            "album-actions" -> TextButton(onClick = { album?.let { localStore.toggleFollow(it) }; sheet = null }) { Text(if (followed.any { it.id == album?.id }) "取消本机收藏" else "收藏到本机") }
                            "more" -> moreTrack?.let { item ->
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { sheet = null; playTrack(item) }) { Text("播放") }
                            }
                            "skip" -> currentTrack?.let { track -> SkipHeadTailPanel(track.albumId, remember { SkipHeadTailStore(this@MainActivity) }) { ok -> if (ok) sheet = null else showTimerFailure("设置保存失败，请重试") } }
                            "speed" -> PlaybackSpeedPanel(playbackSpeed, storageReady && controller != null) { controller?.setPlaybackSpeed(it) }
                            "sleep" -> SleepTimerPanel { minutes -> val c = controller
                                if (c == null) { showTimerFailure("播放器尚未连接") } else {
                                    try {
                                        val pending = c.sendCustomCommand(SessionCommand("com.shixi.client.SLEEP", Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) })
                                        pending.addListener({ try { if (pending.get().resultCode == SessionResult.RESULT_SUCCESS) sleepLabel = if (minutes == 0) "关闭" else "${minutes}分钟" else showTimerFailure("定时设置失败，请重试") } catch (_: Exception) { showTimerFailure("定时设置失败，请重试") } }, androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity))
                                    } catch (_: Exception) { showTimerFailure("定时设置失败，请重试") }
                                }; sheet = null }
                            else -> Unit


                        }
                    }
                }
                CompositionLocalProvider(LocalFeedbackHost provides snackbars) {
                if (syncViewState(cloudState) == SyncViewState.EXPIRED) FloatingNotice("登录已失效，请重新登录", "登录", ::openAccount)
                if (playbackIssue.isNotBlank()) FloatingNotice(playbackIssue, onAction = ::retryPlayback)
                Row(Modifier.fillMaxSize()) {
                if (wide && !showPlayer) AppNavigation(tab, true, ::switchTab)
                Scaffold(modifier = Modifier.weight(1f), snackbarHost = { SnackbarHost(snackbars) }, bottomBar = {
                    Column {
                        if (storageReady && currentTrack != null && !showPlayer) Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "打开播放器") { openPlayer() }.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                CoverArtwork(currentTrack?.cover, Modifier.size(40.dp))
                                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(currentTrack?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium); Text(playbackState, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                SymbolButton(if (controller?.playWhenReady == true) AppIcon.Pause else AppIcon.Play, if (controller?.playWhenReady == true) "暂停" else "播放") { togglePlayback() }
                                SymbolButton(AppIcon.Queue, "选集") { sheet = "queue" }
                            }
                            }
                        }
                        if (!showPlayer && !wide) AppNavigation(tab, false, ::switchTab)
                    }
                }) { padding ->
                    pageState.SaveableStateProvider("tab-$tab") {
                    androidx.compose.runtime.key(tab) {
                    NavDisplay(backStack = stack, onBack = { navigateBack() },
                        // All destinations switch atomically with their surrounding bars. A partial
                        // slide would resize the page while the player bar changes height.
                        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                        popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                        predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                        entryProvider = { key -> NavEntry(key) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (key == "player-$tab") {
                        var draggingPosition by remember(currentTrack?.id, currentTrack?.albumId) { mutableStateOf<Float?>(null) }
                        // Read live progress inside the retained navigation entry, rather than
                        // capturing an outer composition's one-time value in its content lambda.
                        val shownPosition = draggingPosition ?: pendingSeek?.takeIf { it.first == currentTrack?.id && it.second == currentTrack?.albumId }?.third?.toFloat() ?: position
                        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                SymbolButton(AppIcon.Down, "收起", onClick = ::navigateBack)
                                Text(if (controller?.playWhenReady == true) playbackState else "已暂停", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(48.dp))
                            }
                            Spacer(Modifier.height(20.dp)); CoverArtwork(playerAlbum.value?.cover?.ifBlank { currentTrack?.cover.orEmpty() } ?: currentTrack?.cover.orEmpty(), Modifier.width(240.dp).height(240.dp)); Spacer(Modifier.height(28.dp))
                            Text(currentTrack?.title.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(playerAlbum.value?.title.orEmpty().ifBlank { playbackState }, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                playerAlbum.value?.let { item ->
                                    TextButton(onClick = { if (cloudVisible) cloudSync.toggle(item) else openAccount() }) {
                                        Text(if (cloudVisible && cloudState.subscriptions.any { it.id == item.id && it.subscribed }) "已订阅" else "+ 订阅")
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                PlayerAction(AppIcon.Skip, "略过头尾", Modifier.weight(1f), enabled = currentTrack != null) { sheet = "skip" }
                                PlayerAction(AppIcon.Speed, speedText(playbackSpeed), Modifier.weight(1f)) { sheet = "speed" }
                                PlayerAction(AppIcon.Comment, "评论", Modifier.weight(1f), enabled = currentTrack != null, count = commentCount) { sheet = "comments" }
                                currentTrack?.let { track ->
                                    androidx.compose.runtime.key(track.id, accountState.uid) {
                                        EpisodeLikeButton(track.id, accountState.signedIn, accountState.uid, remember { SocialActionsApi(this@MainActivity) }, Modifier.weight(1f), ::openAccount)
                                    }
                                } ?: Spacer(Modifier.weight(1f))
                            }
                            AccessFeedback()
                            if (local.issue.isNotBlank()) FloatingNotice("收听记录保存失败", onAction = ::retryStorage)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SymbolButton(AppIcon.Replay, "后退15秒", storageReady && duration > 0) { seekPlayback(shownPosition.toLong() - 15000) }
                                Column(Modifier.weight(1f)) {
                                    Slider(modifier = Modifier.semantics { contentDescription = "播放进度"; stateDescription = "${shownPosition.toLong() / 60000}分${shownPosition.toLong() / 1000 % 60}秒，共${if (duration > 0) "${duration.toLong() / 60000}分钟" else "时长未知"}" }, value = shownPosition.coerceIn(0f, duration.coerceAtLeast(1f)), onValueChange = { cancelPlaybackIntent(); draggingPosition = it }, onValueChangeFinished = { draggingPosition?.let { seekPlayback(it.toLong()) }; draggingPosition = null }, valueRange = 0f..duration.coerceAtLeast(1f), enabled = storageReady && duration > 0f && currentTrack != null)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${shownPosition.toLong() / 60000}:${(shownPosition.toLong() / 1000 % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(if (duration > 0) "${duration.toLong() / 60000}:${(duration.toLong() / 1000 % 60).toString().padStart(2, '0')}" else "--:--", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                SymbolButton(AppIcon.Forward, "前进30秒", storageReady && duration > 0) { seekPlayback(shownPosition.toLong() + 30000) }
                            }
                            Spacer(Modifier.height(20.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                PlayerAction(AppIcon.Timer, if (sleepLabel == "关闭") "定时关闭" else sleepLabel, Modifier.weight(1f)) { sheet = "sleep" }
                                SymbolButton(AppIcon.Previous, "上一集", controller?.hasPreviousMediaItem() == true || albumExtras.getBoolean("albumBefore")) { cancelPlaybackIntent(); albumCommand("move", -1) }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FilledIconButton(modifier = Modifier.size(56.dp), onClick = ::togglePlayback) { if (controller?.playWhenReady == true && playbackState == "正在加载") CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "正在加载" }, color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) else AppSymbol(if (controller?.playWhenReady == true) AppIcon.Pause else AppIcon.Play, if (controller?.playWhenReady == true) "暂停" else "播放", Modifier.size(24.dp)) } }
                                SymbolButton(AppIcon.Next, "下一集", controller?.hasNextMediaItem() == true || albumExtras.getBoolean("albumAfter")) { cancelPlaybackIntent(); albumCommand("move", 1) }
                                PlayerAction(AppIcon.Queue, "选集", Modifier.weight(1f)) { sheet = "queue" }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    } else if (key == "settings-$tab") Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                        SettingsScreen(accountState.status, playbackSpeed, storageReady && controller != null, cacheMessage, clearingCache, versionName,
                            ::navigateBack, ::openAccount, { sheet = "speed" }, { sheet = "sleep" }, ::clearCovers, cloudState, cloudVisible, { refreshLibrary() }, sleepLabel,
                            personalized = personalized, personalizedAvailable = accountState.signedIn,
                            onPersonalized = { value ->
                                preferenceStore.personalized = value
                                personalized = value
                                scope.launch {
                                    discovery.clearRecommend()
                                    recommendVersion++
                                }
                            })
                    } else if (key == "discover-category-$tab") {
                        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                            DiscoverCategoryPage(discovery, categoryByTab[tab].orEmpty(), { openAlbum(albumItem(it)) }, ::navigateBack)
                        }
                    } else if (key == "discover-anchor-$tab") {
                        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                            val uid = anchorByTab[tab]
                            if (uid == null) EmptyBlock("主播不存在", actionLabel = "返回") { navigateBack() }
                            else DiscoverAnchorDetail(discovery, uid, ::navigateBack) { openAlbum(albumItem(it)) }
                        }
                    } else if (key == "discover-live-$tab") {
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            val roomId = liveByTab[tab]
                            if (roomId == null) EmptyBlock("直播间不存在", actionLabel = "返回") { navigateBack() }
                            else DiscoverLiveRoom(discovery, roomId, ::navigateBack,
                                playing = controller?.playWhenReady == true &&
                                    controller?.sessionExtras?.getString("liveKey").orEmpty().startsWith("live:room:$roomId:"),
                                onToggle = { room ->
                                    if (controller?.playWhenReady == true) controller?.pause()
                                    else playLiveMedia("room", room.roomId, room.liveId, room.name, room.anchor, room.cover)
                                })
                        }
                    } else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                        AccessFeedback()
                        if (connectionFailed) FloatingNotice("播放器连接失败", "重试") { recreate() }
                        if (local.issue.isNotBlank()) FloatingNotice("收听记录保存失败", "重试保存", ::retryStorage)
                        if (key in setOf("album-$tab", "playing-album-$tab")) {
                            if (!loading && albumEntries.isNotEmpty() && requestError != null) FloatingNotice("加载失败，已保留原内容") { album?.let { openAlbum(it, next = albumRequestNext, refresh = !albumRequestNext) } }
                            val albumListState = albumScrolls[tab]
                            LaunchedEffect(albumListState, albumEntries.size, moreTracks, loading, requestError) {
                                snapshotFlow { albumListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
                                    if (last >= albumEntries.size - 1 && albumEntries.isNotEmpty() && moreTracks && !loading && requestError == null) album?.let { openAlbum(it, true) }
                                }
                            }
                            RefreshContent(loading && albumEntries.isNotEmpty(), { if (!loading) album?.let { openAlbum(it, refresh = true) } }, Modifier.fillMaxSize()) {
                            var albumMeta by remember(album?.id) { mutableStateOf<com.shixi.protocol.AlbumDetail?>(null) }
                            LaunchedEffect(album?.id) {
                                albumMeta = album?.id?.let { id -> runCatching { discovery.albumDetail(id) }.getOrNull() }
                            }
                            LazyColumn(state = albumScrolls[tab], modifier = Modifier.fillMaxSize()) {
                                item(key = "album-header") {
                                    Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { SymbolButton(AppIcon.Back, "返回", onClick = ::navigateBack); Text("专辑", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); SymbolButton(AppIcon.More, "专辑更多操作") { sheet = "album-actions" } }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        CoverArtwork(album?.cover, Modifier.size(112.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(album?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                                            albumMeta?.let { meta ->
                                                val line = listOfNotNull(
                                                    meta.anchorName.takeIf { it.isNotBlank() },
                                                    meta.playCount?.let { "${formatCount(it)}播放" },
                                                    meta.trackCount?.let { "${it}集" },
                                                ).joinToString(" · ")
                                                if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CompactAction("播放", enabled = albumEntries.isNotEmpty()) { albumEntries.firstOrNull()?.let { startTrack(it) } }
                                                if (cloudVisible) TextButton(onClick = { album?.let { cloudSync.toggle(it) } }) { Text(if (cloudState.subscriptions.any { it.id == album?.id && it.subscribed }) "已订阅" else "订阅") }
                                                else TextButton(onClick = { album?.let { localStore.toggleFollow(it) } }) { Text(if (followed.any { it.id == album?.id }) "已收藏 · 本机" else "收藏到本机") }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(20.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("节目", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                                        TextButton(onClick = { reversed = !reversed }) { Text(if (reversed) "倒序" else "正序", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        TextButton(onClick = { sheet = "episodes" }) { Text("选集", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    if (loading && albumEntries.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (!loading && albumEntries.isEmpty()) { if (requestError != null) InlineNotice(requestError!!) { album?.let { openAlbum(it) } } else Text("暂无节目") }
                                }
                                items(if (reversed) albumEntries.reversed() else albumEntries, key = { it.id }) { item ->
                                    ListeningRow(item.title, when (item.access) { com.shixi.protocol.ListeningAccess.FREE -> "免费"; com.shixi.protocol.ListeningAccess.VIP_REQUIRED -> "会员"; com.shixi.protocol.ListeningAccess.AUTHORIZED -> "可收听"; com.shixi.protocol.ListeningAccess.PAID_REQUIRED -> "付费节目"; com.shixi.protocol.ListeningAccess.TRIAL -> "试听"; com.shixi.protocol.ListeningAccess.UNAVAILABLE -> "暂不可收听"; else -> "" }, pendingTrackId == item.id, currentTrack?.id == item.id && controller?.playWhenReady == true, { selectTrack(item) }, { moreTrack = item; sheet = "more" })

                                }
                                if (loading && albumEntries.isNotEmpty()) item { CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp)) }
                            } }
                        } else if (key == "search-$tab") {
                            if (requestError != null && !loading && entries.isNotEmpty()) FloatingNotice(searchMessage) { loadSearch(true) }
                            Row(verticalAlignment = Alignment.CenterVertically) { SymbolButton(AppIcon.Back, "返回", onClick = ::navigateBack) }

                                    LazyVerticalGrid(state = searchScroll, columns = GridCells.Adaptive(140.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item(key = "search-header", span = { GridItemSpan(maxLineSpan) }) { Column { Text("搜索", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp)); OutlinedTextField(query, { cancelRequest(); query = it; moreSearch = false; searchMessage = if (entries.isEmpty()) "输入关键词搜索专辑" else "搜索结果" }, Modifier.fillMaxWidth(), label = { Text("搜索专辑") }, singleLine = true)
                                    Row(verticalAlignment = Alignment.CenterVertically) { CompactAction("搜索", !loading && query.isNotBlank(), onClick = { loadSearch() }); Spacer(Modifier.width(12.dp)); if (loading) CircularProgressIndicator(Modifier.size(24.dp)) }
                                    if (requestError != null && !loading && entries.isEmpty()) InlineNotice(searchMessage) { loadSearch() }
                                    else Text(searchMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                    if (local.searches.isNotEmpty()) Text("最近搜索", style = MaterialTheme.typography.titleMedium)
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        local.searches.forEach { saved ->
                                            InputChip(selected = false, onClick = { query = saved; loadSearch() }, label = { Text(saved, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingIcon = {
                                                Box(Modifier.size(24.dp).clickable(onClickLabel = "删除搜索历史 $saved") { localStore.removeSearch(saved) }.semantics { contentDescription = "删除搜索历史 $saved" }, contentAlignment = Alignment.Center) { AppSymbol(AppIcon.Close, null, Modifier.size(14.dp)) }
                                            })
                                        }
                                    }
                                    } }; gridItems(entries, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { item -> AlbumLine(item) { openAlbum(item) } }; if (moreSearch) item { TextButton(enabled = !loading, onClick = { loadSearch(true) }) { Text("加载更多专辑") } } }

                        } else {
                            when (tab) {
                                0 -> {
                                    val recent = effectiveLocal.history.firstOrNull()
                                    val event = controller?.sessionExtras
                                    val latestPlayer = event?.getString("listeningOwner") == (accountManager.lease()?.owner ?: LOCAL_OWNER) && event?.getLong("listeningTrack") == currentTrack?.id && (event?.getLong("listeningAt") ?: 0) >= (recent?.updatedAt ?: 0)
                                    val adjustedCursor = event?.getLong("manualSeekTrack") == currentTrack?.id && event?.getLong("manualSeekAlbum") == currentTrack?.albumId && (event?.getLong("manualSeekAt") ?: 0) >= (recent?.updatedAt ?: 0)
                                    val live = controller?.playWhenReady == true || recent == null || latestPlayer || adjustedCursor
                                    HomeScreen(effectiveLocal, if (live) currentTrack else recent?.item(), if (live) position else recent!!.positionMs.toFloat(), if (live) duration else recent!!.durationMs.toFloat(), playbackState, {
                                        if (!live && recent != null && storageReady && (currentTrack?.id != recent.id || currentTrack?.albumId != recent.albumId || controller?.currentPosition != recent.resumeMs)) {
                                            controller?.setMediaItems(listOf(PlaybackItems.mediaItem(recent.item())), 0, recent.resumeMs)
                                        }
                                        openPlayer()
                                    },
                                    { selectTrack(it.item()) }, { openAlbum(it) }, { libraryFilter = "订阅"; switchTab(2) }, { switchTab(1) }, ::openSettings, ::openSearch, controller?.playWhenReady == true, pendingTrackId, { if (live) togglePlayback() else recent?.let { playTrack(it.item()) } },
                                    if (cloudVisible) cloudState.subscriptions.filter { it.subscribed }.map { it.item() } else emptyList(), cloudVisible && cloudState.refreshing, { refreshLibrary() }, { moreTrack = it.item(); sheet = "more" }, { libraryFilter = "历史"; switchTab(2) })
                                }
                                1 -> DiscoverScreen(discovery, ::openSearch,
                                    onAlbum = { openAlbum(albumItem(it)) },
                                    onCategory = ::openDiscoverCategory,
                                    onAnchor = ::openDiscoverAnchor,
                                    onLiveRoom = { openDiscoverLive(it.roomId) },
                                    onPlayRadio = ::playRadio,
                                    playingRadioId = controller?.sessionExtras?.getString("liveKey").orEmpty()
                                        .takeIf { controller?.playWhenReady == true && it.startsWith("live:radio:") }
                                        ?.split(":")?.getOrNull(2)?.toLongOrNull(),
                                    version = recommendVersion)
                                2 -> LibraryScreen(effectiveLocal, cloudState, cloudVisible, ::openSettings, ::openAccount, { sheet = "queue" }, { refreshLibrary() }, { openAlbum(it) },
                                    { cloudSync.toggle(it.item(), it.owner) }, { selectTrack(it.item()) },
                                    { playCloudHistory(it) }, pendingTrackId, currentTrack?.id, controller?.playWhenReady == true, { switchTab(1) }, libraryFilter, { libraryFilter = it }, ::togglePlayback,
                                    onScan = { startActivity(android.content.Intent(this@MainActivity, WebLoginActivity::class.java)) },
                                    onHistoryMore = { cloudSync.loadMoreHistory() })

                            }
                        }
                    }
                    }
                    } })
                    }
                    }
                }
                }
                }
            }
        }
    }
}
