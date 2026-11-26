package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity

/**
 * The login notice must gate the phone/OTP form, persist per app version,
 * and never appear once accepted for the current version.
 */
class LoginConsentVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: ComponentActivity? = null
        val prefs = targetContext.getSharedPreferences("legal-notice", android.content.Context.MODE_PRIVATE)
        val version = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName.orEmpty()
        val original = prefs.getString("login-consent-version", null)

        fun nodes(text: String): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(uiAutomation.rootInActiveWindow)
            return found
        }
        fun waitFor(label: String, timeoutMs: Long = 15_000, test: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (!test() && SystemClock.uptimeMillis() < end) Thread.sleep(50)
            check(test()) { label }
        }
        fun click(text: String) {
            var done = false
            waitFor(text) {
                if (!done) for (node in nodes(text)) {
                    var parent: AccessibilityNodeInfo? = node
                    while (parent != null) {
                        if (parent.isClickable && parent.isEnabled && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done = true; break }
                        parent = parent.parent
                    }
                    if (done) break
                }
                done
            }
        }
        fun launch() {
            activity = startActivitySync(Intent(targetContext, AccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        }
        fun close() {
            activity?.let { runOnMainSync { it.finish() } }
            activity = null
            Thread.sleep(400)
        }

        try {
            prefs.edit().clear().commit()
            launch()
            waitFor("notice shown") { nodes("登录前请阅读").isNotEmpty() && nodes("我已阅读并同意以上声明").isNotEmpty() }
            check(nodes("获取验证码").isEmpty()) { "OTP button must not exist before consent" }
            check(nodes("第三方客户端").isNotEmpty() && nodes("账号与内容").isNotEmpty() && nodes("隐私与数据").isNotEmpty())

            click("我已阅读并同意以上声明")
            click("同意并继续")
            waitFor("login form after consent") { nodes("获取验证码").isNotEmpty() && nodes("手机号").isNotEmpty() }
            check(prefs.getString("login-consent-version", null) == version) { "consent must be stored for $version" }
            close()

            launch()
            waitFor("form on second launch") { nodes("获取验证码").isNotEmpty() }
            check(nodes("登录前请阅读").isEmpty()) { "accepted version must skip the notice" }
            close()

            prefs.edit().clear().commit()
            launch()
            waitFor("notice shown again") { nodes("登录前请阅读").isNotEmpty() }
            click("不同意，返回")
            waitFor("decline exits") {
                var finished = false
                runOnMainSync { finished = activity?.isFinishing == true || activity?.isDestroyed == true }
                finished
            }
            check(prefs.getString("login-consent-version", null) == null) { "decline must not store consent" }
            activity = null

            result.putString("result", "PASS version=$version")
            finish(-1, result)
        } catch (failure: Throwable) {
            result.putString("failure", failure.stackTraceToString())
            finish(0, result)
        } finally {
            close()
            if (original == null) prefs.edit().remove("login-consent-version").commit()
            else prefs.edit().putString("login-consent-version", original).commit()
        }
    }
}
