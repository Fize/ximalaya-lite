package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.TimeUnit

/** Integration scenarios use real public audio, with no account/SMS or synthetic audio. */
class PlaybackVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun <T> main(block: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun waitFor(label: String, seconds: Int = 25, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(100) }
        error("timed out: $label")
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(text: String) {
        var node = find(text) ?: error("missing $text")
        while (!node.isClickable) node = node.parent ?: error("not clickable $text")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    override fun onStart() {
        val results = Bundle(); val passed = arrayListOf<String>(); var controller: MediaController? = null
        try {
            val future = main { MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
            val player = future.get(20, TimeUnit.SECONDS); controller = player
            fun episode(id: Long, title: String) = MediaItem.Builder().setMediaId("$id:3544:1")
                .setUri("shixi://track/$id/3544/1").setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
            waitFor("local storage ready") { main { player.sessionExtras.getBoolean("storageReady") } }
            main { player.setMediaItems(listOf(episode(222612, "阶段3首集"), episode(222617, "阶段3第二集"))); player.prepare(); player.play() }
            waitFor("real playback") { main { player.isPlaying && player.currentPosition > 1000 } }
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("activity") { find("首页") != null || find("OK") != null || find("收起") != null }
            find("OK")?.let { click("OK"); Thread.sleep(500) }
            waitFor("current programme") { find("阶段3首集") != null }
            if (find("收起") == null) click("阶段3首集")
            Thread.sleep(800)
            find("OK")?.let { click("OK"); Thread.sleep(500) }
            waitFor("previous/next episode controls") { find("上一集") != null && find("下一集") != null }
            val before = main { player.currentPosition }
            main { player.moveMediaItem(0, 1) }
            waitFor("reorder retains current") { main { player.currentMediaItemIndex == 1 && player.currentMediaItem?.mediaId == "222612:3544:1" && player.currentPosition >= before } }
            passed.add("shared_queue_reorder_preserves_playback_and_episode_controls")
            // Given the shared queue, edits and transport controls must agree with the service.
            main { player.seekToPreviousMediaItem() }
            waitFor("previous episode") { main { player.currentMediaItem?.mediaId == "222617:3544:1" && player.isPlaying } }
            main { player.seekToNextMediaItem() }
            waitFor("next episode") { main { player.currentMediaItem?.mediaId == "222612:3544:1" && player.isPlaying } }
            main { player.setPlaybackSpeed(1.5f); player.seekTo(120_000); player.seekForward(); player.seekBack() }
            waitFor("spoken controls") { main { player.currentPosition in 134_000..145_000 && player.playbackParameters.speed == 1.5f } }
            passed.add("episode_navigation_speed_and_15_30_second_controls")
            main { player.pause() }
            waitFor("pause snapshot") { main { PlaybackSnapshots.latest.value?.let { it.queue.size == 2 && it.index == 1 && it.positionMs >= 134_000 && it.speed == 1.5f } == true } }
            check(main { PlaybackSnapshots.latest.value!!.queue.none { it.title.contains("https://") } })
            passed.add("pause_transition_and_throttled_snapshot_interface")
            main { player.removeMediaItem(0) }
            waitFor("remove retains current") { main { player.mediaItemCount == 1 && player.currentMediaItem?.mediaId == "222612:3544:1" } }
            main { player.addMediaItem(episode(222617, "阶段3第二集")); player.setPlaybackSpeed(1f); player.seekTo(0, 0); player.play() }
            waitFor("ready for network") { main { player.isPlaying } }
            fun shell(command: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
            try {
                shell("svc wifi disable"); shell("svc data disable")
                main { player.seekTo(1_280_000) }
                waitFor("offline recovery status", 65) { main { player.sessionExtras.getString("playbackIssue").orEmpty().contains("网络不可用") } }
                main { player.pause() }
                shell("svc wifi enable"); shell("svc data enable"); Thread.sleep(2_000)
                check(main { !player.playWhenReady && !player.isPlaying }) { "network return overrode user pause" }
                main { player.play() }
                waitFor("manual recovery at retained position", 35) { main { player.isPlaying && player.currentPosition >= 1_279_000 } }
                passed.add("offline_then_user_pause_prevents_automatic_resume")
                main { player.seekTo(0) }
                waitFor("playing for automatic recovery") { main { player.isPlaying } }
                shell("svc wifi disable"); shell("svc data disable")
                main { player.seekTo(1_200_000) }
                waitFor("offline error before recovery", 65) { main { player.sessionExtras.getString("playbackIssue").orEmpty().contains("网络不可用") } }
                main { player.moveMediaItem(0, 1) }
                check(main { player.currentMediaItemIndex == 1 && player.currentMediaItem?.mediaId == "222612:3544:1" })
                shell("svc wifi enable"); shell("svc data enable")
                waitFor("automatic recovery preserves position", 40) { main { player.isPlaying && player.currentPosition >= 1_199_000 && player.currentMediaItem?.mediaId == "222612:3544:1" && player.currentMediaItemIndex == 1 && player.sessionExtras.getInt("retryAttempts") in 1..2 } }
                passed.add("network_return_reparses_and_resumes_at_correct_position")
            } finally { shell("svc wifi enable"); shell("svc data enable") }
            val audio = targetContext.getSystemService(android.media.AudioManager::class.java)
            val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({}, android.os.Handler(android.os.Looper.getMainLooper())).build()
            check(main { audio.requestAudioFocus(focus) } == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            waitFor("transient focus loss") { main { !player.isPlaying } }
            main { audio.abandonAudioFocusRequest(focus) }
            waitFor("focus regain") { main { player.isPlaying } }
            check(main { audio.requestAudioFocus(focus) } == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            waitFor("second focus loss") { main { !player.isPlaying } }
            main { player.pause(); audio.abandonAudioFocusRequest(focus) }; Thread.sleep(1000)
            check(main { !player.playWhenReady && !player.isPlaying }) { "focus regain overrode user pause" }
            passed.add("transient_focus_resume_and_user_pause_priority")
            main { player.seekTo(0, 0); player.play() }
            waitFor("rapid selection start") { main { player.isPlaying } }
            main { repeat(8) { player.seekTo(it % 2, 0); player.prepare(); player.play() } }
            waitFor("last selection wins") { main { player.isPlaying && player.currentMediaItem?.mediaId == "222612:3544:1" } }
            passed.add("rapid_selection_keeps_latest_item")
            val permanent = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener({}, android.os.Handler(android.os.Looper.getMainLooper())).build()
            check(main { audio.requestAudioFocus(permanent) } == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            waitFor("permanent focus loss clears intent") { main { !player.isPlaying && !player.playWhenReady } }
            main { audio.abandonAudioFocusRequest(permanent) }; Thread.sleep(1_000)
            check(main { !player.playWhenReady && !player.isPlaying })
            passed.add("permanent_focus_loss_does_not_automatically_resume")
            main { player.play() }; waitFor("ready for noisy callback") { main { player.isPlaying } }
            // Inject the actual registered receiver callback; this does not emulate a physical audio route.
            main {
                val service = checkNotNull(PlaybackService.active)
                val engine = service.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(service)
                val manager = engine.javaClass.getDeclaredField("audioBecomingNoisyManager").apply { isAccessible = true }.get(engine)
                val receiver = manager.javaClass.declaredFields.first { android.content.BroadcastReceiver::class.java.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }.get(manager) as android.content.BroadcastReceiver
                receiver.onReceive(targetContext, Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            }
            waitFor("noisy callback pauses intent") { main { !player.playWhenReady && !player.isPlaying } }
            Thread.sleep(1_200)
            check(main { !player.playWhenReady })
            passed.add("actual_noisy_receiver_callback_pauses_without_auto_resume_injected")
            main { player.setMediaItems(listOf(episode(222612, "阶段3首集"))); player.prepare(); player.play() }
            waitFor("timer ready") { main { player.isPlaying } }
            fun sleep(minutes: Int) = main { player.sendCustomCommand(androidx.media3.session.SessionCommand("com.shixi.client.SLEEP", Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) }) }.get(5, TimeUnit.SECONDS)
            check(sleep(1).resultCode == 0); check(sleep(0).resultCode == 0)
            Thread.sleep(62_000)
            check(main { player.isPlaying }) { "cancelled timer stopped playback" }
            check(sleep(1).resultCode == 0)
            waitFor("one-minute sleep stops playback", 65) { main { !player.playWhenReady && !player.isPlaying } }
            check(targetContext.getSharedPreferences("sleep", 0).getLong("deadline", 0) == 0L)
            passed.add("sleep_timer_cancellation_and_actual_one_minute_expiry")
            main { player.play() }
            waitFor("end-of-queue ready") { main { player.isPlaying && player.duration > 0 } }
            main { player.seekTo((player.duration - 1_500).coerceAtLeast(0)) }
            waitFor("end-of-queue pauses intent", 15) { main { player.playbackState == Player.STATE_ENDED && !player.playWhenReady } }
            passed.add("end_of_queue_stops_without_restart")
            main { player.addMediaItem(episode(9_999_999_999, "无权测试")); player.seekTo(0, (player.duration - 1_500).coerceAtLeast(0)); player.prepare(); player.play() }
            waitFor("next item denied", 30) { main { player.currentMediaItem?.mediaId == "9999999999:3544:1" && !player.playWhenReady && player.sessionExtras.getString("playbackIssue").orEmpty().contains("无明确收听权限") } }
            check(main { player.sessionExtras.getInt("retryAttempts") == 0 })
            passed.add("natural_transition_to_unavailable_item_stops_without_retry")
            resolverScenarios(); passed.add("address_cache_expiry_refresh_and_permission_recheck")
            recoveryScenarios(); passed.add("bounded_retry_stale_intent_and_denied_content")
            results.putStringArrayList("passed", passed); finish(-1, results)
        } catch (t: Throwable) {
            results.putStringArrayList("passed", passed); results.putString("failure", t.javaClass.simpleName + ":" + t.message)
            fun labels(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) + (0 until node.childCount).flatMap { labels(node.getChild(it)) }
            results.putString("window", labels(uiAutomation.rootInActiveWindow).toString()); finish(0, results)
        } finally { controller?.let { main { it.pause(); it.release() } } }
    }

    private fun resolverScenarios() {
        var reads = 0; var urls = 0; var now = 0L; var free = true
        val resolver = PlaybackResolver({ _, _ -> reads++; listOf(com.shixi.protocol.Item(1, "fixture", free, 2)) },
            { urls++; "https://example.invalid/ephemeral-$urls" }, { now })
        val uri = android.net.Uri.parse("shixi://track/1/2/1")
        val first = resolver.resolve(uri); check(resolver.resolve(uri) == first && reads == 1)
        resolver.invalidate(uri); check(resolver.resolve(uri) != first && reads == 2)
        now = 120_001; resolver.resolve(uri); check(reads == 3)
        free = false; resolver.invalidate(uri)
        check(runCatching { resolver.resolve(uri) }.exceptionOrNull() is PlaybackAccessException && urls == 3)
        check(runCatching { resolver.resolve(android.net.Uri.parse("https://example.invalid/audio")) }.isFailure)
    }

    private fun recoveryScenarios() {
        var ready = true; var online = false; var prepared = 0; var position = 25_000L
        var item = MediaItem.Builder().setMediaId("1:2:1").setUri("shixi://track/1/2/1").build()
        val fake = java.lang.reflect.Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlayWhenReady" -> ready
                "getCurrentMediaItem" -> item
                "getCurrentMediaItemIndex", "getPlaybackSuppressionReason" -> 0
                "getCurrentPosition" -> position
                "prepare" -> { prepared++; null }
                "seekTo" -> { position = (args!!.last() as Long); null }
                "pause" -> { ready = false; null }
                else -> when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null }
            }
        } as Player
        val resolver = PlaybackResolver({ _, _ -> emptyList() }, { error("not called") })
        val recovery = main { PlaybackRecovery(fake, resolver, android.os.Handler(android.os.Looper.getMainLooper()), { online }, {}) }
        fun error() = androidx.media3.common.PlaybackException("synthetic expired address", java.io.IOException(), androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        main { recovery.onError(error()); check(recovery.issue.contains("网络不可用")); ready = false; recovery.cancel(); online = true; recovery.tryPending() }
        Thread.sleep(1200); check(prepared == 0)
        main { ready = true; recovery.onError(error()) }; Thread.sleep(1300)
        check(prepared == 1 && position == 25_000L)
        main { recovery.onError(error()) }; Thread.sleep(3300)
        check(prepared == 2)
        main { recovery.onError(error()); check(recovery.issue.contains("上限")) }; Thread.sleep(1200); check(prepared == 2)
        main { recovery.userIntent(); recovery.onError(error()); item = MediaItem.Builder().setMediaId("3:2:1").build() }
        Thread.sleep(1300); check(prepared == 2)
        main {
            recovery.cancel(); recovery.onError(androidx.media3.common.PlaybackException("denied", PlaybackAccessException(), androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            check(!ready && recovery.issue.contains("无明确收听权限")); recovery.cancel()
        }
    }
}
