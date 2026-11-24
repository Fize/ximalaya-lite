package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import com.shixi.protocol.ContentApi
import com.shixi.protocol.Item
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CopyOnWriteArrayList

/** Reproduces the guest "account changed" failure and checks the aligned request/playback path. */
class EpisodeAccessVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val out = Bundle(); val checks = mutableListOf<String>(); var activity: ComponentActivity? = null
        fun nodes(label: String): List<AccessibilityNodeInfo> {
            uiAutomation.clearCache(); val found = mutableListOf<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null) return
                if (n.text?.toString() == label || n.contentDescription?.toString() == label || label == "EDITABLE" && n.isEditable) found.add(n)
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(uiAutomation.rootInActiveWindow); return found
        }
        fun waitFor(label: String, timeout: Long = 35000, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(80) }
            error(label)
        }
        fun click(label: String) {
            waitFor(label) {
                nodes(label).any { n ->
                    var p: AccessibilityNodeInfo? = n; var ok = false
                    while (!ok && p != null) { if (p.isClickable) ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p = p.parent }
                    ok
                }
            }
        }
        fun engine(): Player? = PlaybackService.active?.let {
            PlaybackService::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(it) as Player
        }
        fun playing(): Boolean { var yes = false; runOnMainSync { yes = engine()?.isPlaying == true }; return yes }
        try {
            val api = AccountContentApi(targetContext)
            val rows = api.tracks(78672695L, 1)
            check(rows.size == 20)
            val first = rows.first()
            check(first.title.startsWith("第0章")) { "first=${first.title}" }
            check(first.free && first.access == com.shixi.protocol.ListeningAccess.FREE) { "access=${first.access}" }
            check(api.resolve(first).startsWith("https://"))
            checks.add("guest free episode resolves with device cookie")

            val cookies = CopyOnWriteArrayList<String>()
            val fixture = OkHttpClient.Builder().addInterceptor { chain ->
                cookies += chain.request().header("Cookie").orEmpty()
                val body = if (chain.request().url.encodedPath == "/v1/track/baseInfo")
                    """{"ret":0,"trackId":12,"albumId":34,"isPaid":false,"isPublic":true,"status":1,"playUrl64":"https://fixture.xmcdn.com/audio.m4a"}"""
                else
                    """{"ret":0,"data":{"list":[{"trackId":12,"title":"fixture free","isPaid":false,"isPublic":true,"isHoldCopyright":true}]}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val probe = AccountContentApi(targetContext, http = fixture)
            val probeRows = probe.tracks(34L, 1)
            check(probeRows.single().access == com.shixi.protocol.ListeningAccess.FREE)
            check(cookies.single().contains("1&_device=") && !cookies.single().contains("1&_token="))
            check(probe.resolve(Item(12, "fixture free", true, 34)) == "https://fixture.xmcdn.com/audio.m4a")
            checks.add("anonymous cookie present and no token; free grant uses fresh metadata")

            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
            click("搜索专辑")
            waitFor("search field") {
                nodes("EDITABLE").any {
                    it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "黄易优秀长篇武侠小说 大唐双龙传")
                    })
                }
            }
            click("搜索")
            val album = "黄易优秀长篇武侠小说《大唐双龙传》"
            click(album)
            val episode = "第0章 大唐双龙传内容介绍"
            click("播放 $episode")
            waitFor("guest episode playing") { playing() }
            runOnMainSync { engine()!!.pause() }
            checks.add("search -> album -> first free episode plays without account notice")
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.externalCacheDir, "episode-access.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } catch (failure: Throwable) {
            out.putString("error", failure.stackTraceToString())
        } finally {
            out.putStringArrayList("checks", ArrayList(checks))
            activity?.finish()
            finish(if (out.containsKey("error")) 1 else 0, out)
        }
    }
}
