package com.shixi.client

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import android.net.Uri
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.shixi.protocol.ContentApi
import com.shixi.protocol.EpisodeAccess
import com.shixi.protocol.ListeningAccess
import com.shixi.protocol.ContentAccessException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Real public-server regressions plus contradictory/missing flag cases. No account credentials. */
class AccessVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun <T> main(block: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    override fun onStart() {
        val out = Bundle(); val passed = arrayListOf<String>(); var controller: MediaController? = null
        try {
            fun state(json: String) = EpisodeAccess.classify(JSONObject(json))
            check(state("{}") == ListeningAccess.UNKNOWN)
            check(state("{isPaid:false,isSample:false,isHoldCopyright:true}") == ListeningAccess.FREE)
            check(state("{isPaid:false,isHoldCopyright:true}") == ListeningAccess.FREE)
            check(state("{isPaid:false,isPublic:true}") == ListeningAccess.FREE)
            check(state("{isPaid:true,isFree:true,isAuthorized:true,vipFreeType:1}") == ListeningAccess.FREE)
            check(state("{isPaid:true,isFree:false,isAuthorized:true,vipFreeType:1}") == ListeningAccess.AUTHORIZED)
            check(state("{isPaid:true,isFree:false,isAuthorized:false,vipFreeType:1}") == ListeningAccess.VIP_REQUIRED)
            check(state("{isPaid:true,isAuthorized:false,vipFreeType:0}") == ListeningAccess.PAID_REQUIRED)
            check(state("{isPaid:true,isFree:true,isAuthorized:false,vipFreeType:1}") == ListeningAccess.VIP_REQUIRED)
            check(state("{isPaid:false,isSample:true,isHoldCopyright:true}") == ListeningAccess.TRIAL)
            check(state("{isPaid:false,isSample:false,isHoldCopyright:false}") == ListeningAccess.UNAVAILABLE)
            check(state("{isPaid:true,isFree:true,isAuthorized:true,isPublic:false}") == ListeningAccess.UNAVAILABLE)
            passed.add("10_missing_contradictory_free_vip_paid_authorized_trial_unavailable_cases")
            val api = ContentApi(); val rows = api.tracks(13507836)
            val free = rows.first { it.id == 75598151L }; val locked = rows.first { it.id == 76285512L }
            check(free.free && free.playable && free.access == ListeningAccess.FREE) { "free=${free.access}" }
            check(!locked.free && !locked.playable && locked.access == ListeningAccess.VIP_REQUIRED) { "locked=${locked.access}" }
            passed.add("same_vip_album_free_prefix_playable_later_episode_locked")
            check(runCatching { api.resolve(locked.copy(free = true, access = ListeningAccess.FREE)) }.exceptionOrNull() is ContentAccessException)
            val resolver = PlaybackResolver(api::tracks, api::resolve)
            check(runCatching { resolver.resolve(Uri.parse("shixi://track/76285512/13507836/1")) }.exceptionOrNull() is ContentAccessException)
            check(resolver.resolve(Uri.parse("shixi://track/75598151/13507836/1")).scheme == "https")
            passed.add("fresh_metadata_rejects_forged_free_flag_and_service_denies_locked_episode")
            val ordinary = api.tracks(257813).first { it.id == 2788075L }
            check(ordinary.free && ordinary.playable && api.resolve(ordinary).startsWith("https://"))
            passed.add("ordinary_free_episode_address_regression")
            val future = main { MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
            val player = future.get(20, TimeUnit.SECONDS); controller = player
            fun waitFor(label: String, check: () -> Boolean) {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
                while (System.nanoTime() < until) { if (check()) return; Thread.sleep(100) }; error("timeout $label")
            }
            waitFor("storage") { main { player.sessionExtras.getBoolean("storageReady") } }
            main { player.setMediaItem(PlaybackItems.mediaItem(free)); player.prepare(); player.play() }
            waitFor("actual_paid_album_free_prefix_audio_decoder") { main { player.isPlaying && player.currentPosition > 3000 && player.duration > 1_400_000 } }
            passed.add("real_media3_paid_album_free_prefix_playing_duration_over_1400s")
            main { player.setMediaItem(PlaybackItems.mediaItem(locked)); player.prepare(); player.play() }
            waitFor("locked episode stopped without retries") { main {
                !player.playWhenReady && player.sessionExtras.getString("playbackIssue").orEmpty().contains("VIP")
            } }
            check(main { player.sessionExtras.getInt("retryAttempts") == 0 })
            passed.add("real_media3_locked_vip_stops_with_specific_message_no_retry")
            main { player.setMediaItem(PlaybackItems.mediaItem(free)); player.prepare(); player.play() }
            waitFor("free episode works after permission denial") { main { player.isPlaying && player.currentPosition > 1000 } }
            passed.add("denial_does_not_poison_next_authorized_playback")
            out.putStringArrayList("passed", passed); out.putLong("positionMs", main { player.currentPosition }); out.putLong("durationMs", main { player.duration })
            finish(-1, out)
        } catch (t: Throwable) { out.putStringArrayList("passed", passed); out.putString("failure", t.javaClass.simpleName + ":" + t.message); finish(0, out) }
        finally { controller?.let { main { it.release() } } }
    }
}
