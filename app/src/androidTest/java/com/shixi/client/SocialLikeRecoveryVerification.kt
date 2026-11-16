package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/** All requests are intercepted; the account and content are synthetic. */
class SocialLikeRecoveryVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out = Bundle()
        var activity: MainActivity? = null
        fun nodes(label: String): List<AccessibilityNodeInfo> {
            uiAutomation.clearCache(); val found = mutableListOf<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; if (n.text?.toString() == label || n.contentDescription?.toString() == label) found.add(n); for (i in 0 until n.childCount) walk(n.getChild(i)) }
            walk(uiAutomation.rootInActiveWindow); return found
        }
        fun waitFor(label: String) { val until = SystemClock.uptimeMillis() + 15000; while (SystemClock.uptimeMillis() < until) { if (nodes(label).isNotEmpty()) return; Thread.sleep(80) }; error("missing $label") }
        fun click(label: String) { waitFor(label); check(nodes(label).any { node -> var n: AccessibilityNodeInfo? = node; var done = false; while (n != null && !done) { if (n.isClickable && n.isEnabled) done = n.performAction(AccessibilityNodeInfo.ACTION_CLICK); n = n.parent }; done }) }
        try {
            val gateway = object : AccountGateway {
                override fun send(mobile: String) = Unit
                override fun login(mobile: String, code: String) = AccountSession(71, "fixture-token")
                override fun check(session: AccountSession) = true
                override fun refresh(session: AccountSession) = session
            }
            val store = object : SessionStore {
                var saved: AccountSession? = null
                override fun read() = saved
                override fun write(session: AccountSession) { saved = session }
                override fun clear() { saved = null }
            }
            val accounts = AccountManager(gateway, store, targetContext.getSharedPreferences("social-like-fixture", Context.MODE_PRIVATE), 42)
            fun idle() { val end=SystemClock.uptimeMillis()+6000; while(accounts.state.value.busy && SystemClock.uptimeMillis()<end) Thread.sleep(20); check(!accounts.state.value.busy) }
            idle(); accounts.login("fixture", "fixture"); idle(); check(accounts.state.value.signedIn)
            val reads=AtomicInteger(); val writes=AtomicInteger(); val comments=AtomicInteger()
            val transport=OkHttpClient.Builder().addInterceptor { chain ->
                val req=chain.request(); check(req.url.host=="mobile.ximalaya.com")
                val status: Int; val body: String
                if(req.method=="GET") { status=if(reads.incrementAndGet()==1) 500 else 200; body="""{"ret":0,"data":[{"trackId":12,"isLike":true}]}""" }
                else if(req.url.encodedPath == "/comment-mobile/v1/create") {
                    val form = req.body as okhttp3.FormBody
                    check((0 until form.size).any { form.name(it)=="trackId" && form.value(it)=="12" })
                    check((0 until form.size).any { form.name(it)=="content" && form.value(it)=="受控评论" })
                    comments.incrementAndGet(); status=200; body="""{"ret":0}"""
                } else { writes.incrementAndGet(); status=200; body="""{"ret":0}""" }
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()
            }.build()
            val api=SocialActionsApi(targetContext, accounts, transport)
            activity=startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            runOnMainSync { activity!!.setContent { MaterialTheme { EpisodeLikeButton(12, true, 71, api, login={ error("unexpected login") }) } } }
            waitFor("重试点赞"); check(writes.get()==0)
            click("重试点赞"); waitFor("已赞"); check(reads.get()==2 && writes.get()==0)
            click("已赞"); waitFor("点赞"); check(writes.get()==1)
            api.comment(checkNotNull(accounts.lease()), 12, "受控评论"); check(comments.get()==1)
            out.putString("result", "PASS failed status shows retry; retry reads status without write; confirmed like toggles once; comment fields submitted once")
            finish(-1,out)
        } catch (t: Throwable) { out.putString("failure",t.stackTraceToString()); finish(0,out) }
        finally { activity?.let { runOnMainSync { it.finish() } } }
    }
}
