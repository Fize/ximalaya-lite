@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shixi.client

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var engine: ExoPlayer
    private lateinit var controlled: Player
    private lateinit var recovery: PlaybackRecovery
    private lateinit var albumQueue: AlbumPlaybackQueue
    private lateinit var catalogue: PlaybackCatalogue
    private lateinit var connectivity: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var skipStore: SkipHeadTailStore
    private val skipPolicy = SkipHeadTailPolicy()
    private var snapshotAt = 0L
    private var snapshotRevision = 0L
    private var destroyed = false
    private lateinit var store: ListeningStore
    private var storeEpoch = 0L
    private var accountLease: AccountLease? = null
    private var accountTrack: Long = 0
    private lateinit var cloudSync: CloudSync
    private var storageReady = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val heard = mutableSetOf<Long>()
    private val durations = mutableMapOf<Long, Long>()
    private var manualSeekTrack = 0L
    private var manualSeekAlbum = 0L
    private var manualSeekAt = 0L
    private fun knownDuration(): Long {
        if (engine.duration > 0) return engine.duration
        val track = engine.currentMediaItem?.let(PlaybackItems::item) ?: return 0
        val owner = AccountManager.get(this).lease()?.owner ?: LOCAL_OWNER
        return durations[track.id] ?: (store.state.value.history + store.state.value.accountHistory)
            .firstOrNull { it.id == track.id && it.albumId == track.albumId && it.owner == owner && it.durationMs > 0 }?.durationMs ?: 0
    }
    private val listeningEvents = ListeningEvents()
    private var explicitListeningEvent = false
    private var lastListeningEvent: HistoryRow? = null
    private fun history(item: MediaItem?, position: Long, duration: Long, completed: Boolean = false): HistoryRow? {
        val track = item?.let(PlaybackItems::item) ?: return null
        if (track.id !in heard) return null
        val row = HistoryRow(owner = accountLease?.owner ?: LOCAL_OWNER, id = track.id, albumId = track.albumId, page = track.page, title = track.title,
            cover = track.cover, positionMs = position.coerceAtLeast(0), durationMs = (duration.takeIf { it > 0 } ?: durations[track.id] ?: store.state.value.history.firstOrNull { it.id == track.id }?.durationMs ?: 0).coerceAtLeast(0),
            completed = completed, updatedAt = System.currentTimeMillis())
        return listeningEvents.stamp(row, explicitListeningEvent).also { explicitListeningEvent = false; lastListeningEvent = it }
    }
    private fun restoreRecords() {
        if (storageReady) return
        serviceScope.launch {
            try {
                val saved = store.restore().await()
                if (destroyed || storageReady) return@launch
                val selected = saved.queue.getOrNull(saved.index)
                engine.setMediaItems(listOfNotNull(selected).map(PlaybackItems::mediaItem), 0, saved.positionMs)
                if (selected != null && saved.manualSeekAt > 0) { manualSeekTrack = selected.id; manualSeekAlbum = selected.albumId; manualSeekAt = saved.manualSeekAt }
                albumQueue.reset()
                engine.setPlaybackSpeed(saved.speed)
                engine.pause() // Do not prepare: no authorization/address request before explicit play.
                storageReady = true
                snapshot()
                selected?.let { item -> serviceScope.launch(Dispatchers.IO) { runCatching { catalogue.tracks(item.albumId, item.page) } } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { extras() }
        }
    }
    private fun unavailable(): ListenableFuture<*> = Futures.immediateFailedFuture<Void>(IllegalStateException("正在恢复本机记录"))
    companion object { var active: PlaybackService? = null; private set }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { if (!destroyed) { recovery.tryPending(); cloudSync.resume() } } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) handler.post { if (!destroyed) { recovery.tryPending(); cloudSync.resume() } }
        }
    }
    private fun online(): Boolean = connectivity.activeNetwork?.let { network -> connectivity.getNetworkCapabilities(network)
        ?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } } == true
    private fun alarm(): PendingIntent = PendingIntent.getBroadcast(this, 0, Intent(this, SleepReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun setSleep(minutes: Int) {
        val manager = getSystemService(AlarmManager::class.java)
        val prefs = getSharedPreferences("sleep", MODE_PRIVATE)
        val oldDeadline = prefs.getLong("deadline", 0)
        val oldBoot = prefs.getInt("boot", -1)
        val deadline = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        try {
            if (deadline > 0) manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarm()) else manager.cancel(alarm())
            check(prefs.edit().putLong("deadline", deadline).putInt("boot", Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)).commit())
        } catch (failure: Exception) {
            runCatching {
                if (oldDeadline > 0 && oldBoot == Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)) manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, oldDeadline, alarm()) else manager.cancel(alarm())
                prefs.edit().putLong("deadline", oldDeadline).putInt("boot", oldBoot).commit()
            }
            throw failure
        }
    }
    private fun checkSleep() {
        val prefs = getSharedPreferences("sleep", MODE_PRIVATE)
        val deadline = prefs.getLong("deadline", 0)
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (prefs.getInt("boot", -2) != boot) { if (deadline > 0) setSleep(0); return }
        if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) stopForSleep()
    }
    private fun extras() {
        if (destroyed || !::session.isInitialized) return
        val last = PlaybackSnapshots.latest.value
        session.setSessionExtras(Bundle().apply {
            putInt("albumGeneration", if (::albumQueue.isInitialized) albumQueue.version else 0)
            putBoolean("albumReady", if (::albumQueue.isInitialized) albumQueue.ready else false)
            putBoolean("albumLoading", if (::albumQueue.isInitialized) albumQueue.loading else false)
            putBoolean("albumBefore", if (::albumQueue.isInitialized) albumQueue.before else false)
            putBoolean("albumAfter", if (::albumQueue.isInitialized) albumQueue.after else false)
            putString("albumIssue", if (::albumQueue.isInitialized) albumQueue.issue else "")
            putString("listeningOwner", lastListeningEvent?.owner.orEmpty())
            putLong("listeningTrack", lastListeningEvent?.id ?: 0)
            putLong("listeningAt", lastListeningEvent?.updatedAt ?: 0)
            putBoolean("storageReady", storageReady)
            putBoolean("liveMode", PlaybackItems.isLive(engine.currentMediaItem))
            putString("liveKey", PlaybackItems.liveKey(engine.currentMediaItem))
            putString("durationMediaId", engine.currentMediaItem?.mediaId.orEmpty())
            putLong("durationMs", knownDuration())
            putLong("manualSeekTrack", manualSeekTrack)
            putLong("manualSeekAlbum", manualSeekAlbum)
            putLong("manualSeekAt", manualSeekAt)
            putString("storageIssue", if (::store.isInitialized) store.state.value.issue else "")
            putLong("savedRevision", if (::store.isInitialized) store.state.value.savedRevision else 0)
            putString("playbackIssue", recovery.issue); putInt("retryAttempts", recovery.attempts)
            putLong("snapshotRevision", snapshotRevision); putLong("snapshotPositionMs", last?.positionMs ?: 0)
            putInt("snapshotQueueCount", last?.queue?.size ?: 0)
        })
    }
    private fun snapshot() {
        if (!::engine.isInitialized || !storageReady) return
        // Live has no queue/timeline; keep the last album snapshot restorable.
        if (PlaybackItems.isLive(engine.currentMediaItem)) { extras(); return }
        snapshotAt = SystemClock.elapsedRealtime(); snapshotRevision++
        // Gapless transitions can keep isPlaying=true without a second state-change callback.
        if (engine.isPlaying) engine.currentMediaItem?.let(PlaybackItems::item)?.let {
            if (it.id != accountTrack) accountTrack = it.id
            heard.add(it.id)
        }
        val knownDuration = knownDuration()
        if (knownDuration > 0) engine.currentMediaItem?.let(PlaybackItems::item)?.let { durations[it.id] = knownDuration }
        val saved = PlaybackSnapshot((0 until engine.mediaItemCount).mapNotNull { PlaybackItems.item(engine.getMediaItemAt(it)) },
            engine.currentMediaItemIndex, engine.currentPosition.coerceAtLeast(0), engine.playbackParameters.speed, snapshotAt, manualSeekAt)
        PlaybackSnapshots.publish(saved)
        val record = history(engine.currentMediaItem, engine.currentPosition, engine.duration, engine.playbackState == Player.STATE_ENDED)
        store.save(storeEpoch, saved, record, snapshotRevision)
        if (::cloudSync.isInitialized && record != null) cloudSync.record(accountLease, record)
        extras()
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            checkSleep()
            if (storageReady && engine.isPlaying) {
                val album = engine.currentMediaItem?.let(PlaybackItems::item)?.albumId ?: 0L
                skipPolicy.target(engine.currentPosition, engine.duration, skipStore.get(album))?.let(engine::seekTo)
            }
            if (storageReady && engine.isPlaying && SystemClock.elapsedRealtime() - snapshotAt >= 10_000) snapshot()
            handler.postDelayed(this, 1_000)
        }
    }
    override fun onCreate() {
        super.onCreate(); active = this
        skipStore = SkipHeadTailStore(this)
        store = ListeningStore.get(this); storeEpoch = store.attachPlayer(); cloudSync = CloudSync.get(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        val accounts = AccountManager.get(this)
        val content = AccountContentApi(this)
        catalogue = PlaybackCatalogue(content::tracks)
        val discoveryApi = com.shixi.protocol.DiscoveryApi()
        val resolver = PlaybackResolver(catalogue::tracks, content::resolve, owner = accounts::contentIdentity,
            liveAddress = { kind, id, extra ->
                val url = when (kind) {
                    "room" -> discoveryApi.liveStream(extra)
                    "radio" -> discoveryApi.radioHome().modules.asSequence()
                        .flatMap { it.stations.asSequence() }.firstOrNull { it.id == id }?.streamUrl.orEmpty()
                    else -> ""
                }
                if (url.isBlank()) throw java.io.IOException("直播流暂不可用")
                url
            })
        // Keep media traffic separate from the account client. HTTPS cannot downgrade
        // on redirects; OkHttp reuses healthy connections and races available routes.
        val mediaHttp = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).followSslRedirects(false)
            .eventListenerFactory { call ->
                val id = System.identityHashCode(call)
                object : okhttp3.EventListener() {
                    private fun event(name: String) = StartupTiming.mark("media_net_${id}_$name")
                    override fun dnsStart(call: okhttp3.Call, domainName: String) = event("dns_start")
                    override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) = event("dns_end")
                    override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) = event("connect_start")
                    override fun secureConnectStart(call: okhttp3.Call) = event("tls_start")
                    override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = event("tls_end")
                    override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) = event("connection_acquired")
                    override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) = event("request_sent")
                    override fun responseHeadersStart(call: okhttp3.Call) = event("response_start")
                    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = event("failed_${ioe.javaClass.simpleName}")
                }
            }.build()
        val http = OkHttpDataSource.Factory(mediaHttp)
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            try { spec.withUri(resolver.resolve(spec.uri)) }
            catch (failure: PlaybackAccessException) { throw failure }
            catch (failure: com.shixi.protocol.ContentAccessException) { throw failure }
            catch (_: Exception) { throw IOException("无法获取播放地址") }
        }
        engine = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DataSource.Factory { AccountMediaDataSource(source.createDataSource(), accounts::contentIdentity) })
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)))
            .setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(30_000).build()
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        engine.setHandleAudioBecomingNoisy(true)
        engine.setWakeMode(C.WAKE_MODE_NETWORK)
        recovery = PlaybackRecovery(engine, resolver, handler, ::online, ::extras)
        albumQueue = AlbumPlaybackQueue(engine, serviceScope, accounts::contentIdentity, catalogue::tracks, ::extras)
        controlled = object : ForwardingSimpleBasePlayer(engine) {
            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                if (!storageReady) return unavailable()
                recovery.userIntent()
                albumQueue.cancelIntent()
                if (playWhenReady) {
                    StartupTiming.begin()
                    accountLease = AccountManager.get(this@PlaybackService).lease()
                    accountTrack = engine.currentMediaItem?.let(PlaybackItems::item)?.id ?: 0
                    albumQueue.ensure()
                    val last = engine.currentMediaItem?.let(PlaybackItems::item)?.let { item -> store.state.value.history.firstOrNull { it.id == item.id } }
                    val restoredEnd = last?.completed == true && last.durationMs > 0 && engine.currentPosition >= last.durationMs - 1000
                    if (engine.playbackState == Player.STATE_ENDED || restoredEnd) engine.seekToDefaultPosition(0)
                    if (engine.playbackState == Player.STATE_IDLE || engine.playerError != null) engine.prepare()
                }
                return super.handleSetPlayWhenReady(playWhenReady)
            }
            override fun handleSetPlaybackParameters(parameters: androidx.media3.common.PlaybackParameters): ListenableFuture<*> {
                if (!storageReady) return unavailable()
                return super.handleSetPlaybackParameters(parameters)
            }
            override fun handlePrepare(): ListenableFuture<*> { if (!storageReady) return unavailable(); recovery.userIntent(); return super.handlePrepare() }
            override fun handleStop(): ListenableFuture<*> { if (!storageReady) return unavailable(); recovery.cancel(); albumQueue.cancelIntent(); return super.handleStop() }
            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                if (!storageReady) return unavailable()
                // Live HLS has no timeline; ignore seeks instead of queuing album moves.
                if (PlaybackItems.isLive(engine.currentMediaItem)) return Futures.immediateVoidFuture()
                recovery.userIntent(); albumQueue.cancelIntent()
                if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                    albumQueue.move(-1); return Futures.immediateVoidFuture()
                }
                if (seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                    albumQueue.move(1); return Futures.immediateVoidFuture()
                }
                return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
            override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
                if (!storageReady) return unavailable()
                return super.handleAddMediaItems(index, mediaItems)
            }
            override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
                if (!storageReady) return unavailable()
                return super.handleRemoveMediaItems(fromIndex, toIndex)
            }
            override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
                if (!storageReady) return unavailable()
                return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
            }
            override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
                if (!storageReady) return unavailable()
                recovery.cancel()
                // Selecting a saved record only previews its cursor; it is not a listen.
                snapshot()
                engine.pause()
                heard.clear()
                manualSeekTrack = 0; manualSeekAlbum = 0; manualSeekAt = 0
                lastListeningEvent = null
                explicitListeningEvent = false
                accountLease = accounts.lease()
                accountTrack = 0
                val selected = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
                val result = super.handleSetMediaItems(listOfNotNull(selected).toMutableList(), 0, startPositionMs)
                albumQueue.reset(); albumQueue.ensure()
                return result
            }
        }
        val activity = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, controlled).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val result = super.onConnect(session, controller)
                if (controller.packageName != packageName) return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(result.availableSessionCommands)
                    .setAvailablePlayerCommands(result.availablePlayerCommands.buildUpon().remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(result.availableSessionCommands.buildUpon()
                    .add(SessionCommand("com.shixi.client.CURSOR", Bundle.EMPTY)).add(SessionCommand("com.shixi.client.ALBUM", Bundle.EMPTY)).add(SessionCommand("com.shixi.client.SAVE", Bundle.EMPTY)).add(SessionCommand("com.shixi.client.SLEEP", Bundle.EMPTY)).add(SessionCommand("com.shixi.client.RETRY", Bundle.EMPTY)).build()).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED))
                return when (command.customAction) {
                    "com.shixi.client.CURSOR" -> {
                        val track = engine.currentMediaItem?.let(PlaybackItems::item)
                        val duration = knownDuration()
                        if (!storageReady || track == null || duration <= 0)
                            Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_INVALID_STATE))
                        else if (args.getLong("trackId") != track.id || args.getLong("albumId") != track.albumId || args.getLong("positionMs", -1) < 0)
                            Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
                        else {
                            recovery.userIntent(); albumQueue.cancelIntent()
                            manualSeekTrack = track.id; manualSeekAlbum = track.albumId
                            manualSeekAt = maxOf(System.currentTimeMillis(), manualSeekAt + 1)
                            // IDLE timelines do not expose the normal seek command. Moving the
                            // saved cursor must not prepare, authorize or start the audio.
                            engine.seekTo(engine.currentMediaItemIndex, args.getLong("positionMs").coerceAtMost(duration))
                            snapshot()
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    }
                    "com.shixi.client.ALBUM" -> {
                        if (!storageReady) return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_INVALID_STATE))
                        when (args.getString("action")) {
                            "load" -> albumQueue.more(args.getInt("direction", 1))
                            "retry" -> albumQueue.retry()
                            "move" -> { recovery.userIntent(); albumQueue.move(args.getInt("direction", 1)) }
                            else -> albumQueue.ensure()
                        }
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "com.shixi.client.SAVE" -> {
                        if (storageReady) snapshot() else restoreRecords()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "com.shixi.client.SLEEP" -> {
                        val minutes = args.getInt("minutes", 0)
                        if (minutes !in 0..120) Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
                        else { try { setSleep(minutes); Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) } catch (_: Exception) { Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_UNKNOWN)) } }
                    }
                    "com.shixi.client.RETRY" -> {
                        if (!storageReady) { restoreRecords(); Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                        else if (engine.currentMediaItem == null) Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_INVALID_STATE))
                        else { recovery.userIntent(); resolver.invalidate(engine.currentMediaItem?.localConfiguration?.uri); engine.prepare(); engine.play(); Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                    }
                    else -> Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
                }
            }
        }).build()
        engine.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { recovery.onError(error); snapshot() }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                if (!ready) { recovery.cancel(clearIssue = false); albumQueue.cancelIntent() }
                snapshot()
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (item?.let(PlaybackItems::item)?.let { it.id == manualSeekTrack && it.albumId == manualSeekAlbum } != true) { manualSeekTrack = 0; manualSeekAlbum = 0; manualSeekAt = 0 }
                skipPolicy.reset(); recovery.cancel(); snapshot(); handler.post { if (!destroyed) albumQueue.prefetch() }
            }
            override fun onIsPlayingChanged(playing: Boolean) { if (playing) {
                explicitListeningEvent = true
                StartupTiming.mark("playing")
                recovery.onPlaying(); snapshot()
            } }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) { recovery.tryPending() }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { snapshot() }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // A paused seek after restart updates an existing listened breakpoint too.
                if (storageReady && reason == Player.DISCONTINUITY_REASON_SEEK && old.mediaItem?.mediaId == new.mediaItem?.mediaId) {
                    new.mediaItem?.let(PlaybackItems::item)?.takeIf { item -> (store.state.value.history + store.state.value.accountHistory).any { it.id == item.id && it.owner == (accountLease?.owner ?: LOCAL_OWNER) } }?.let { heard.add(it.id) }
                }
                history(old.mediaItem, old.positionMs, if (old.mediaItem?.mediaId == new.mediaItem?.mediaId) engine.duration else C.TIME_UNSET,
                    reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)?.let { row ->
                    store.history(storeEpoch, row)
                    cloudSync.record(accountLease, row)
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK) explicitListeningEvent = true
                snapshot()
            }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) { snapshot() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) StartupTiming.mark("buffering")
                if (state == Player.STATE_READY) StartupTiming.mark("ready")
                if (state == Player.STATE_ENDED) { if (!albumQueue.ended()) engine.pause(); snapshot() }
            }
        })
        connectivity.registerDefaultNetworkCallback(networkCallback)
        serviceScope.launch { store.state.collectLatest { extras() } }
        var contentIdentity = accounts.contentIdentity()
        serviceScope.launch { accounts.state.collectLatest {
            val next = accounts.contentIdentity()
            if (next != contentIdentity) {
                contentIdentity = next
                val interrupted = engine.playWhenReady
                resolver.invalidate(null)
                albumQueue.cancelIntent(); engine.pause(); engine.stop(); albumQueue.reset()
                // Keep the interrupted history attached to its original owner; a new play intent captures the new lease.
                accountTrack = 0
                recovery.accountChanged(interrupted)
                snapshot()
            }
        } }
        checkSleep(); restoreRecords(); handler.post(heartbeat)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    fun stopForSleep() {
        recovery.cancel(); engine.pause(); setSleep(0); snapshot()
    }
    override fun onDestroy() {
        destroyed = true
        if (active === this) active = null
        if (::engine.isInitialized) snapshot()
        if (::albumQueue.isInitialized) albumQueue.close()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::connectivity.isInitialized) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (::recovery.isInitialized) recovery.cancel()
        if (::session.isInitialized) session.release()
        if (::controlled.isInitialized) controlled.release()
        super.onDestroy()
    }
}
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("sleep", Context.MODE_PRIVATE)
        val deadline = prefs.getLong("deadline", 0)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (deadline > 0 && prefs.getInt("boot", -2) == boot && SystemClock.elapsedRealtime() >= deadline) {
            PlaybackService.active?.stopForSleep()
            prefs.edit().remove("deadline").apply()
        }
    }
}
