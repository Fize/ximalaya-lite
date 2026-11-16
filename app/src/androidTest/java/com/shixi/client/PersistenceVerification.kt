package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.app.UiAutomation
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.shixi.protocol.Item
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs on the isolated AVD. Separate invocations deliberately cross process lifetimes. */
class PersistenceVerification : Instrumentation() {
    private var phase = "seed"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "seed"; start() }
    private fun <T> main(block: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }; @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun waitFor(label: String, seconds: Int = 30, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(80) }
        error("timed out: $label")
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(text: String) {
        waitFor("click $text", 8) {
            var node = find(text) ?: return@waitFor false
            while (!node.isClickable) node = node.parent ?: return@waitFor false
            node.isVisibleToUser && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    override fun onStart() {
        val results = Bundle(); val passed = arrayListOf<String>(); var player: MediaController? = null
        try {
            if (phase == "seed") databaseChecks(passed)
            val future = main { MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
            val controller = future.get(20, TimeUnit.SECONDS); player = controller
            val store = ListeningStore.get(targetContext)
            waitFor("storage startup") { main { controller.sessionExtras.getBoolean("storageReady") } }
            fun durable() {
                waitFor("durable acknowledgement") { main {
                    val extras = controller.sessionExtras
                    extras.getLong("snapshotRevision") > 0 && extras.getLong("savedRevision") == extras.getLong("snapshotRevision")
                } }
                runBlocking { store.awaitIdle() }; check(store.state.value.issue.isBlank())
            }
            if (phase == "empty_history") {
                main { controller.clearMediaItems() }
                waitFor("empty queue committed") { main { controller.mediaItemCount == 0 && controller.sessionExtras.getInt("snapshotQueueCount") == 0 } }; durable()
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitFor("empty history navigation") { find("资料库") != null || find("OK") != null }
                find("OK")?.let { click("OK") }; click("资料库")
                waitFor("saved history without queue") { find("阶段4真实音频第一集") != null }; click("阶段4真实音频第一集")
                waitFor("history adds first queue item and resumes real audio") { main { controller.mediaItemCount == 1 && controller.currentMediaItem?.mediaId == "222612:3544:1" && controller.isPlaying && controller.currentPosition in 64_000..72_000 } }
                passed.add("actual_empty_queue_history_add_rebuilds_internal_uri_and_resumes_saved_65_seconds")
                main { controller.pause() }; durable(); main { controller.release() }; player = null
                results.putStringArrayList("passed", passed); finish(android.app.Activity.RESULT_OK, results); return
            }
            if (phase == "completed_seed") {
                main {
                    controller.setMediaItems(listOf(PlaybackItems.mediaItem(Item(222617, "阶段4真实音频第二集", false, 3544)), PlaybackItems.mediaItem(Item(222612, "阶段4真实音频第一集", false, 3544))), 1, 0)
                    controller.prepare(); controller.play()
                }
                waitFor("final episode ready") { main { controller.isPlaying && controller.duration > 10_000 } }
                main { controller.seekTo(controller.duration - 1000) }
                waitFor("final episode completed") { main { controller.playbackState == Player.STATE_ENDED && !controller.playWhenReady } }
                waitFor("completion committed") { store.state.value.history.firstOrNull { it.id == 222612L }?.completed == true }; durable()
                passed.add("actual_completed_last_episode_saved_for_fresh_process_history_replay")
                main { controller.release() }; player = null
                results.putStringArrayList("passed", passed); finish(android.app.Activity.RESULT_OK, results); return
            }
            if (phase == "completed_restore") {
                check(main { controller.currentMediaItemIndex == 1 && controller.playbackState == Player.STATE_IDLE && !controller.playWhenReady })
                check(store.state.value.history.first { it.id == 222612L }.completed)
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitFor("fresh history navigation") { find("资料库") != null || find("OK") != null }
                find("OK")?.let { click("OK") }; click("资料库")
                waitFor("fresh completed history") { find("已听完") != null }; click("已听完")
                waitFor("fresh completed current history plays chosen last item at zero") { main { controller.currentMediaItemIndex == 1 && controller.currentMediaItem?.mediaId == "222612:3544:1" && controller.isPlaying && controller.currentPosition < 8000 } }
                passed.add("force_stopped_unprepared_current_completed_history_replays_selected_item_at_zero_not_first_queue_item")
                main { controller.pause() }; durable(); main { controller.release() }; player = null
                results.putStringArrayList("passed", passed); finish(android.app.Activity.RESULT_OK, results); return
            }
            if (phase == "natural") {
                var trueEvents = 0; var cutTrueEvents = -1; var playingAtCut = false
                val engine = main { val service = checkNotNull(PlaybackService.active); service.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(service) as Player }
                val trace = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { if (playing) trueEvents++ }
                    override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && item?.mediaId == "222617:3544:1") {
                            cutTrueEvents = trueEvents; playingAtCut = engine.isPlaying
                        }
                    }
                }
                main {
                    engine.addListener(trace)
                    controller.setMediaItems(listOf(PlaybackItems.mediaItem(Item(222612, "阶段4自然接续第一集", false, 3544)), PlaybackItems.mediaItem(Item(222617, "阶段4自然接续第二集", false, 3544))))
                    controller.prepare(); controller.play()
                }
                waitFor("natural first ready") { main { controller.isPlaying && controller.duration > 10_000 } }
                main { controller.seekTo(controller.duration - 7000) }
                waitFor("next real episode naturally playing", 30) { main { controller.currentMediaItem?.mediaId == "222617:3544:1" && controller.isPlaying && controller.currentPosition >= 1800 } }
                val noNewTrueCallback = main { playingAtCut && cutTrueEvents >= 0 && trueEvents == cutTrueEvents }
                main { controller.pause() }; durable()
                val row = store.state.value.history.firstOrNull { it.id == 222617L } ?: error("naturally played next episode missing from history")
                check(row.title == "阶段4自然接续第二集" && row.positionMs in 1500..10_000 && !row.completed)
                check(noNewTrueCallback) { "expected prebuffered gapless cut without another isPlaying=true callback" }
                passed.add("real_prebuffered_natural_cut_without_second_isPlaying_true_records_next_episode_and_pause_position=${row.positionMs}")
                main { engine.removeListener(trace); controller.release() }; player = null
                results.putStringArrayList("passed", passed); finish(android.app.Activity.RESULT_OK, results); return
            }
            if (phase == "completed") {
                val first = Item(222612, "阶段4真实音频第一集", false, 3544)
                val second = Item(222617, "阶段4真实音频第二集", false, 3544)
                main { controller.setMediaItems(listOf(PlaybackItems.mediaItem(second), PlaybackItems.mediaItem(first)), 1, 0); controller.prepare(); controller.play() }
                waitFor("completed scenario ready") { main { controller.isPlaying && controller.duration > 10_000 } }
                main { controller.seekTo(controller.duration - 1000) }
                waitFor("completed queue") { main { controller.playbackState == Player.STATE_ENDED && !controller.playWhenReady } }; durable()
                check(store.state.value.history.first { it.id == first.id }.completed)
                main { controller.stop(); controller.prepare(); controller.play() }
                waitFor("prepare before play replays completed queue") { main { controller.isPlaying && controller.currentMediaItemIndex == 0 && controller.currentPosition < 8000 } }
                passed.add("completed_cursor_prepare_before_play_replays_queue_without_second_tap")
                main { controller.seekTo(1, 0) }
                waitFor("last item for completed history") { main { controller.isPlaying && controller.duration > 10_000 && controller.currentMediaItemIndex == 1 } }
                main { controller.seekTo(controller.duration - 1000) }
                waitFor("last completed history") { main { controller.playbackState == Player.STATE_ENDED && !controller.playWhenReady } }; durable()
                main { controller.stop() }; durable()
                check(store.state.value.history.first { it.id == first.id }.completed)
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitFor("completed history home") { find("资料库") != null || find("OK") != null }
                find("OK")?.let { click("OK") }; click("资料库")
                waitFor("completed history row") { find("已听完") != null }; click("已听完")
                waitFor("completed current history restarts selected episode") { main { controller.isPlaying && controller.currentMediaItemIndex == 1 && controller.currentPosition < 8000 } }
                passed.add("actual_completed_current_history_row_restarts_selected_episode_at_zero")
                main { controller.pause() }; durable()
                results.putStringArrayList("passed", passed); main { controller.release() }; player = null
                finish(android.app.Activity.RESULT_OK, results); return
            }
            if (phase == "seed") {
                val first = Item(222612, "阶段4真实音频第一集", false, 3544)
                val second = Item(222617, "阶段4真实音频第二集", false, 3544)
                main { controller.setMediaItems(listOf(PlaybackItems.mediaItem(first), PlaybackItems.mediaItem(second))); controller.prepare(); controller.play() }
                waitFor("real first audio") { main { controller.isPlaying && controller.currentPosition > 1500 } }
                main { controller.seekTo(65_000); controller.pause() }
                durable()
                check(store.state.value.history.first { it.id == first.id }.positionMs in 64_000..67_000)
                passed.add("pause_saves_real_listening_history_and_65_second_breakpoint")
                val faultDb = ListeningDatabase.open(targetContext)
                runBlocking(Dispatchers.IO) { faultDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_store BEFORE INSERT ON queue BEGIN SELECT RAISE(ABORT, 'test rejected disk write'); END") }
                main { controller.seekTo(75_000) }
                waitFor("store reports failure") { store.state.value.issue.isNotBlank() }
                runBlocking(Dispatchers.IO) { check(faultDb.records().cursor()?.positionMs in 64_000L..67_000L); faultDb.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_store"); faultDb.close() }
                main { controller.seekTo(65_000) }; durable()
                check(store.state.value.history.first { it.id == first.id }.positionMs in 64_000..67_000)
                passed.add("live_writer_failure_reports_retains_last_good_data_and_next_seek_recovers")
                main { controller.seekTo(1, 0); controller.play() }
                waitFor("second real audio") { main { controller.isPlaying && controller.currentPosition > 1500 } }
                main { controller.seekTo(42_000); controller.pause(); controller.moveMediaItem(1, 0); controller.setPlaybackSpeed(1.5f) }
                durable()
                val history = store.state.value.history
                check(history.first { it.id == first.id }.positionMs in 64_000..67_000)
                check(history.first { it.id == first.id }.durationMs > 70_000 && history.first { it.id == second.id }.durationMs > 44_000)
                check(history.first { it.id == second.id }.positionMs in 41_000..44_000)
                check(main { controller.currentMediaItemIndex == 0 && controller.getMediaItemAt(0).mediaId == "222617:3544:1" })
                if (store.state.value.follows.none { it.id == 3544L }) store.toggleFollow(Item(3544, "本机关注验证")); store.search("持久化搜索验证"); store.search("删除搜索验证"); store.removeSearch("删除搜索验证")
                runBlocking { store.awaitIdle() }
                check(store.state.value.searches.contains("持久化搜索验证") && !store.state.value.searches.contains("删除搜索验证"))
                passed.add("per_episode_positions_queue_reorder_speed_follows_and_search_history_saved")
            } else {
                check(main { controller.mediaItemCount == 2 && controller.getMediaItemAt(0).mediaId == "222617:3544:1" && controller.getMediaItemAt(1).mediaId == "222612:3544:1" })
                check(main { controller.currentMediaItemIndex == 0 && controller.currentPosition in 41_000..44_000 && controller.playbackParameters.speed == 1.5f })
                check(main { !controller.playWhenReady && !controller.isPlaying && controller.playbackState == Player.STATE_IDLE && controller.playerError == null })
                check(store.state.value.history.size >= 2 && store.state.value.follows.any { it.id == 3544L } && "持久化搜索验证" in store.state.value.searches)
                val before = store.state.value.history
                // Opening/restoring is not another listen, and offline restoration must not prepare media.
                Thread.sleep(1500)
                check(main { controller.playbackState == Player.STATE_IDLE && controller.playerError == null && !controller.playWhenReady })
                check(store.state.value.history == before)
                passed.add("$phase: persisted_queue_cursor_speed_history_follows_searches_restore_paused_without_prepare")
                if (phase == "paused_seek") {
                    val initiallySeekable = main { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
                    main { controller.prepare() } // Explicit request, remains paused; no implicit startup prepare.
                    waitFor("explicitly prepared paused seekable media") { main { controller.playbackState == Player.STATE_READY && controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && !controller.playWhenReady } }
                    main { controller.seekTo(55_000) }
                    waitFor("seek command reached service") { main { controller.sessionExtras.getLong("snapshotPositionMs") == 55_000L } }; durable()
                    check(store.state.value.history.first { it.id == 222617L }.positionMs == 55_000L)
                    main { controller.seekTo(1, 0) }
                    waitFor("switch command reached service") { main { controller.currentMediaItemIndex == 1 && controller.sessionExtras.getLong("snapshotPositionMs") == 0L } }; durable()
                    check(store.state.value.history.first { it.id == 222617L }.positionMs == 55_000L)
                    main { controller.seekTo(0, 42_000) }
                    waitFor("reset command reached service") { main { controller.sessionExtras.getLong("snapshotPositionMs") == 42_000L } }; durable()
                    check(main { !controller.playWhenReady && !controller.isPlaying })
                    passed.add("restored_explicitly_prepared_paused_seek_updates_existing_history_before_switch_initiallySeekable=$initiallySeekable")
                }
                if (phase == "resume" || phase == "progress") {
                    main { controller.play() }
                    waitFor("explicit resume real audio", 50) { main { controller.isPlaying && controller.currentPosition > 44_000 } }
                    check(main { controller.currentMediaItem?.localConfiguration?.uri.toString().startsWith("shixi://track/") })
                    passed.add("explicit_resume_reauthorizes_fresh_internal_uri_and_outputs_decoded_real_audio")
                    if (phase == "progress") {
                        val initial = store.state.value.history.first { it.id == 222617L }.positionMs
                        waitFor("throttled durable progress", 20) { store.state.value.history.first { it.id == 222617L }.positionMs > initial + 6000 }
                        val saved = store.state.value.history.first { it.id == 222617L }.positionMs
                        val actual = main { controller.currentPosition }
                        check(actual - saved in 0..3000)
                        passed.add("periodic_playing_progress_commits_without_pause_actual=$actual-saved=$saved")
                    }
                    // Return to a fixed durable cursor for subsequent crash/reboot tests.
                    main { controller.pause(); controller.seekTo(42_000) }; durable()
                }
            }
            durable()
            if (phase == "ui") {
                uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitFor("home or compatibility dialog") { find("继续收听") != null || find("OK") != null }
                find("OK")?.let { click("OK") }
                waitFor("home history") { find("最近播放") != null && find("听到1分5秒") != null }
                val before = store.state.value.history
                click("资料库")
                waitFor("local history library") { find("收听历史") != null && find("以下记录仅保存在本机，不属于账号云端，也不会自动上传。") != null }
                click("清理封面缓存（最多50MB）")
                waitFor("cache clear result") { find("封面缓存已清理，历史、断点和队列已保留") != null }
                check(store.state.value.history == before)
                uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                waitFor("rotated history") { find("收听历史") != null }
                check(main { controller.mediaItemCount == 2 && controller.currentPosition == 42_000L && !controller.playWhenReady })
                uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
                waitFor("portrait navigation after rotation") { find("搜索") != null }
                click("搜索")
                waitFor("search history restored in actual UI") { find("本机搜索历史") != null && find("持久化搜索验证") != null }
                passed.add("actual_home_library_search_history_cache_clear_and_rotation_preserve_paused_queue_and_positions")
            }
            if (phase == "seed") {
                // Already accepted writes must precede the next owner's read; stale disposed callbacks are rejected.
                val current = main { PlaybackSnapshots.latest.value!! }
                val oldEpoch = store.attachPlayer()
                store.save(oldEpoch, current, null, 90_001)
                store.attachPlayer()
                val reload = store.restore()
                store.save(oldEpoch, current.copy(positionMs = 999_000), null, 90_002)
                val restored = runBlocking { reload.await().also { store.awaitIdle() } }
                check(restored.positionMs == 42_000L && restored.queue.first().id == 222617L)
                passed.add("accepted_fifo_writes_precede_restore_and_disposed_player_callbacks_cannot_overwrite_new_owner")
            }
            results.putStringArrayList("passed", passed); results.putString("phase", phase)
            results.putLong("positionMs", main { controller.currentPosition })
            results.putString("storageIssue", store.state.value.issue)
            main { controller.release() }; player = null
            finish(android.app.Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("failure", error.stackTraceToString()); results.putStringArrayList("passed", passed)
            player?.let { controller -> runCatching { main { controller.pause(); controller.release() } } }
            finish(android.app.Activity.RESULT_CANCELED, results)
        }
    }
    private fun databaseChecks(passed: MutableList<String>) = runBlocking {
        withContext(Dispatchers.IO) {
            val file = java.io.File(targetContext.noBackupFilesDir, "verification.db")
            targetContext.deleteDatabase(file.absolutePath)
            var db = ListeningDatabase.open(targetContext, file.name)
            val dao = db.records()
            val first = Item(222612, "事务原队列", false, 3544)
            val snapshot = PlaybackSnapshot(listOf(first), 0, 15_000, 1f, 0)
            dao.save(snapshot, null)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test BEFORE INSERT ON queue WHEN NEW.id=999 BEGIN SELECT RAISE(ABORT, 'test disk write rejection'); END")
            var failed = false
            try { dao.save(snapshot.copy(queue = listOf(first.copy(id = 999)), positionMs = 9000), null) } catch (_: Exception) { failed = true }
            check(failed && dao.queue().single().id == first.id && dao.cursor()?.positionMs == 15_000L)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test")
            dao.save(snapshot.copy(positionMs = 18_000), null)
            check(dao.cursor()?.positionMs == 18_000L)
            passed.add("real_room_transaction_abort_rolls_back_queue_and_cursor_then_next_write_succeeds")
            dao.putCursor(CursorRow(owner = "account:verification", index = 7, positionMs = 999))
            dao.save(snapshot.copy(positionMs = 21_000), null)
            check(dao.cursor("account:verification")?.positionMs == 999L && dao.cursor()?.positionMs == 21_000L)
            db.close(); db = ListeningDatabase.open(targetContext, file.name)
            check(db.records().queue().single().id == first.id && db.records().cursor()?.positionMs == 21_000L)
            db.close(); targetContext.deleteDatabase(file.absolutePath)
            passed.add("v1_schema_reopen_validation_and_guest_owner_transaction_isolation")
        }
    }
}
