package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** Empty view fixture only; never deletes the database or changes account credentials. */
class UiEmptyVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val store = ListeningStore.get(targetContext)
        val field = ListeningStore::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val state = field.get(store) as MutableStateFlow<ListeningData>
        var saved: ListeningData? = null
        var activity: android.app.Activity? = null
        fun nodes(text: String): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) { if (node == null) return; if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node); for (i in 0 until node.childCount) visit(node.getChild(i)) }
            visit(uiAutomation.rootInActiveWindow); return found
        }
        fun waitFor(label: String, predicate: () -> Boolean) {
            val end = System.nanoTime() + 15_000_000_000L
            while (!predicate() && System.nanoTime() < end) Thread.sleep(30)
            check(predicate()) { label }
        }
        fun click(text: String) {
            var done = false
            waitFor(text) { if (!done) for (node in nodes(text)) { var p: AccessibilityNodeInfo? = node
                while (p != null) { if (p.isClickable && p.isEnabled && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done = true; break }; p = p.parent }
                if (done) break
            }; done }
        }
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("loaded real home") { state.value.loaded && nodes("继续收听").isNotEmpty() }
            val original = state.value; saved = original
            state.value = original.copy(history = emptyList(), follows = emptyList())
            waitFor("home empty recent") { nodes("听过的节目会出现在这里").isNotEmpty() }
            check(nodes("订阅喜欢的专辑，随时回来听").isNotEmpty())
            click("我的")
            waitFor("empty following") { nodes("还没有订阅").isNotEmpty() }
            click("历史")
            waitFor("empty history") { nodes("还没有收听记录").isNotEmpty() }
            click("设置")
            waitFor("product settings") { nodes("存储与关于").isNotEmpty() }
            check(nodes("技术诊断").isEmpty())
            click("账号与同步")
            waitFor("login form") { nodes("获取验证码").isNotEmpty() }
            check(nodes("使用喜马拉雅账号登录").isNotEmpty())
            check(nodes("返回").isNotEmpty())
            result.putString("result", "PASS actual empty home sections and filtered library, database preserved")
            finish(-1, result)
        } catch (error: Throwable) { result.putString("failure", error.message); finish(0, result) }
        finally { saved?.let { state.value = it }; activity?.let { runOnMainSync { it.finish() } } }
    }
}
