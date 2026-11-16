package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import com.shixi.protocol.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class FeedbackVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out=Bundle();val checks=mutableListOf<String>();var activity:ComponentActivity?=null
        fun nodes(label:String):List<AccessibilityNodeInfo> { uiAutomation.clearCache();val found=mutableListOf<AccessibilityNodeInfo>();fun walk(n:AccessibilityNodeInfo?){if(n==null)return;if(n.text?.toString()==label || n.contentDescription?.toString()==label || label=="EDITABLE" && n.isEditable)found.add(n);for(i in 0 until n.childCount)walk(n.getChild(i))};walk(uiAutomation.rootInActiveWindow);return found }
        fun waitFor(label:String,timeout:Long=35000,condition:()->Boolean){val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(80)};error(label)}
        fun click(label:String){waitFor(label){nodes(label).any { n->var p:AccessibilityNodeInfo?=n;var ok=false;while(!ok&&p!=null){if(p.isClickable)ok=p.performAction(AccessibilityNodeInfo.ACTION_CLICK);p=p.parent};ok}}}
        fun engine():Player?=PlaybackService.active?.let { PlaybackService::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(it) as Player }
        fun playing():Boolean {var yes=false;runOnMainSync{yes=engine()?.isPlaying==true};return yes}
        try {
            var mode="grant";var fallbackCalls=0
            val fixture=ContentApi(transport={ request ->
                val fallback=request.url.encodedPath.startsWith("/mobile/others/")
                val body=if(!fallback) """{"ret":${if(mode=="login") 50 else 924}}""" else {
                    fallbackCalls++
                    """{"ret":0,"album":{"albumId":34},"tracks":{"list":[{"trackId":${if(mode=="wrong") 13 else 12},"albumId":34,"title":"fixture","isPaid":true,"isAuthorized":${mode!="denied"},"isFree":true,"isPublic":true,"isHoldCopyright":true,"isSample":${mode=="sample"},"playUrl64":"https://fixture.xmcdn.com/audio.m4a"}]}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
            })
            val item=Item(12,"fixture",true,34)
            check(fixture.resolve(item)=="https://fixture.xmcdn.com/audio.m4a")
            for(m in listOf("denied","wrong","sample")){mode=m;check(runCatching{fixture.resolve(item)}.isFailure)}
            mode="login";val before=fallbackCalls;check(runCatching{fixture.resolve(item)}.isFailure);check(fallbackCalls==before)
            for (code in listOf(50,401,403)) check(userContentMessage(ContentAccessException(ListeningAccess.UNKNOWN, serviceCode=code)) == "登录状态需要更新，请重试")
            check(userContentMessage(ContentAccessException(ListeningAccess.UNKNOWN)) == "账号状态已变化，请重新选择节目")
            checks.add("known login failures retain account recovery instead of reselecting episode")
            checks.add("924 fallback requires fresh exact grant; denied/wrong/sample/login rejected")
            val live=AccountContentApi(targetContext)
            val search=ContentApi().search("鸣龙");check(search.any{it.id==125160552L})
            val first=live.tracks(125160552L).first();check(first.id==993425122L && first.free)
            check(live.resolve(first).startsWith("https://"))
            checks.add("live Minglong search, list and free episode resolution")
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
            click("搜索专辑")
            waitFor("query field"){nodes("EDITABLE").any { it.isEditable && it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"鸣龙")}) }}
            click("搜索");click(search.first{it.id==125160552L}.title);click("播放 ${first.title}")
            waitFor("Minglong real engine playing"){playing()};Thread.sleep(2200)
            runOnMainSync{check(engine()!!.currentPosition>1000);engine()!!.pause()}
            checks.add("native UI search -> Minglong album -> first episode playing")
            runOnMainSync{activity!!.onBackPressedDispatcher.onBackPressed()};runOnMainSync{activity!!.onBackPressedDispatcher.onBackPressed()}
            click("我的");click("历史");click("播放 ${first.title}");waitFor("history resumes Minglong"){playing()}
            runOnMainSync{engine()!!.pause()};checks.add("native history resumes same Minglong episode"); uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(targetContext.externalCacheDir,"minglong-history.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            // Real pagination failure: editing a query must dismiss its obsolete retry.
            click("收听");click("搜索专辑")
            fun setQuery(value:String) { waitFor("editable search field"){nodes("EDITABLE").any { it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}) }} }
            fun scroll(forward:Boolean) {
                fun walk(n:AccessibilityNodeInfo?):Boolean { if(n==null)return false;if(n.isScrollable && n.performAction(if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;for(i in 0 until n.childCount)if(walk(n.getChild(i)))return true;return false }
                uiAutomation.clearCache();check(walk(uiAutomation.rootInActiveWindow));Thread.sleep(180)
            }
            setQuery("明朝");click("搜索")
            val historySearch=ContentApi().search("明朝");check(historySearch.size==20)
            waitFor("first search loaded"){nodes(historySearch.first().title).isNotEmpty()}
            repeat(8){if(nodes("加载更多专辑").isEmpty())scroll(true)}
            try {
                uiAutomation.executeShellCommand("svc wifi disable").close();uiAutomation.executeShellCommand("svc data disable").close();Thread.sleep(600)
                click("加载更多专辑")
                waitFor("pagination error"){nodes("连接失败，请检查网络后重试").isNotEmpty() || nodes("连接超时，请重试").isNotEmpty()}
                repeat(8){if(nodes("EDITABLE").isEmpty())scroll(false)}
                check(nodes("连接失败，请检查网络后重试").isNotEmpty() || nodes("连接超时，请重试").isNotEmpty())
                setQuery("鸣龙")
                waitFor("edited query cancels obsolete error"){nodes("连接失败，请检查网络后重试").isEmpty() && nodes("连接超时，请重试").isEmpty() && nodes("重试").isEmpty()}
            } finally { uiAutomation.executeShellCommand("svc wifi enable").close();uiAutomation.executeShellCommand("svc data enable").close() }
            checks.add("editing search query cancels failed pagination retry on actual screen")
            // Dedicated component state fixture verifies overlay placement and retry without live network timing.
            var show by mutableStateOf(false);var retried=false
            runOnMainSync{activity!!.setContent { MaterialTheme { val host=remember{SnackbarHostState()};CompositionLocalProvider(LocalFeedbackHost provides host){Scaffold(snackbarHost={SnackbarHost(host)}){padding->Column(Modifier.fillMaxSize().padding(padding)){Text("固定历史行");if(show) FloatingNotice("连接失败，请检查网络后重试"){retried=true;show=false};Text("下一条历史")}}}} }}
            waitFor("fixture visible"){nodes("固定历史行").isNotEmpty()};val rect=android.graphics.Rect();nodes("下一条历史").first().getBoundsInScreen(rect)
            runOnMainSync{show=true};waitFor("floating failure"){nodes("连接失败，请检查网络后重试").isNotEmpty()}
            val after=android.graphics.Rect();nodes("下一条历史").first().getBoundsInScreen(after);check(rect==after)
            uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(targetContext.externalCacheDir,"failure-overlay.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            click("重试");waitFor("retry callback"){retried};checks.add("failure floats without moving history rows; retry actionable")
            runOnMainSync{activity!!.finish()};out.putString("result","PASS")
        } catch(t:Throwable){out.putString("result","FAIL");out.putString("failure",t.stackTraceToString())}
        out.putStringArrayList("checks",ArrayList(checks));finish(if(out.getString("result")=="PASS") -1 else 0,out)
    }
}
