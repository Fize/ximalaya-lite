package com.shixi.client

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.zxing.RGBLuminanceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** All transport fixtures are synthetic. No SMS, real login or browser grant is performed. */
class WebLoginVerification : Instrumentation() {
    private var phase = "full"
    override fun onCreate(arguments: Bundle?) { phase = arguments?.getString("phase") ?: "full"; super.onCreate(arguments); start() }
    private val qr = "https://mobile.ximalaya.com/passport-sign-mobile/qrcode/login/${"A".repeat(32)}/qrcode-7172636f6465?to=喜马拉雅WEB"
    private fun waitFor(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        error(label)
    }
    private fun nodes(label: String): List<AccessibilityNodeInfo> {
        uiAutomation.clearCache()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) found.add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(uiAutomation.rootInActiveWindow)
        return found
    }
    private fun click(label: String) {
        waitFor("click $label") {
            val clicked = nodes(label).any { node ->
                var item: AccessibilityNodeInfo? = node
                var clicked = false
                while (item != null && !clicked) {
                    if (item.isClickable && item.isEnabled) clicked = item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    item = item.parent
                }
                clicked
            }
            if (!clicked) {
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    repeat(node.childCount) { if (scroll(node.getChild(it))) return true }
                    return false
                }
                scroll(uiAutomation.rootInActiveWindow)
            }
            clicked
        }
    }
    private fun shell(command: String): ByteArray = uiAutomation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    private fun openScanner(): android.app.Activity {
        val monitor = addMonitor(WebLoginActivity::class.java.name, null, false)
        try {
            runOnMainSync { targetContext.startActivity(Intent(targetContext, WebLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000)) { "scanner Activity launch timed out" }
            // Synthetic accounts/QR only: permit screenshots of the actual camera fixture.
            runOnMainSync { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            waitFor("scanner page is visible") {
                if (nodes("Android App Compatibility").isNotEmpty()) {
                    listOf("Don't show again", "Don't Show Again", "OK").forEach { label ->
                        nodes(label).forEach { node -> var item: AccessibilityNodeInfo? = node; while (item != null) { if (item.isClickable) { item.performAction(AccessibilityNodeInfo.ACTION_CLICK); break }; item = item.parent } }
                    }
                }
                nodes("扫一扫").isNotEmpty() || listOf("While using the app", "Only this time", "Don’t allow", "Don't allow").any { nodes(it).isNotEmpty() }
            }
            return activity
        } finally { removeMonitor(monitor) }
    }
    override fun onStart() {
        val result = Bundle()
        val completed = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val singleton = AccountManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        var activity: android.app.Activity? = null
        var flow: WebLoginFlow? = null
        var mainActivity: android.app.Activity? = null
        fun passed(name: String) { completed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        try {
            val store = object : SessionStore { var saved: AccountSession? = null; override fun read() = saved; override fun write(session: AccountSession) { saved = session }; override fun clear() { saved = null } }
            val gateway = object : AccountGateway {
                var uid = 9101L
                override fun send(mobile: String) { error("no real SMS") }
                override fun login(mobile: String, code: String) = AccountSession(uid, "synthetic-web-login-token")
                override fun check(session: AccountSession) = true
            }
            val account = AccountManager(gateway, store, targetContext.getSharedPreferences("web-login-fixture", Context.MODE_PRIVATE), 0, scope)
            waitFor("initial account") { !account.state.value.busy }
            account.login("synthetic", "synthetic"); waitFor("fixture login") { !account.state.value.busy && account.state.value.signedIn }
            singleton.set(null, account)
            if (phase == "first_allow") {
                targetContext.getSharedPreferences("scanner-permission", Context.MODE_PRIVATE).edit().clear().commit()
                check(targetContext.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                activity = openScanner()
                waitFor("first-use native camera request") { nodes("While using the app").isNotEmpty() }
                val recreation = addMonitor(WebLoginActivity::class.java.name, null, false)
                try {
                    runOnMainSync { activity!!.recreate() }
                    waitFor("native permission survives recreation") { nodes("While using the app").isNotEmpty() }
                    click("While using the app")
                    activity = checkNotNull(recreation.waitForActivityWithTimeout(10_000))
                    runOnMainSync { activity!!.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
                } finally { removeMonitor(recreation) }
                waitFor("first permission grant starts real camera", 30_000) { nodes("确认登录").isNotEmpty() }
                check(nodes("使用当前账号（ID：9101）登录").isNotEmpty() && nodes("从相册选择").isEmpty())
                click("取消"); waitFor("first-use cancel closes scanner") { activity!!.isFinishing || activity!!.isDestroyed }
                passed("first_use_allow_native_permission_starts_real_camera_without_settings")
                result.putString("result", "PASS ${completed.size} checks: ${completed.joinToString()}")
                finish(-1, result)
                return
            }
            check(WebLoginCode.parse(qr) != null)
            val attacks = listOf(qr.replace("https:", "http:"), qr.replace("mobile.ximalaya.com", "mobile.ximalaya.com.evil.test"),
                qr.replace("mobile.ximalaya.com", "evil.test@mobile.ximalaya.com"), qr.replace("mobile.ximalaya.com", "mobile.ximalaya.com:444"),
                "$qr#fragment", "$qr&to=喜马拉雅WEB", "$qr&redirect=https://evil.test", qr.replace("喜马拉雅WEB", "车载"),
                qr.replace("A".repeat(32), "A".repeat(31)), qr.replace("/qrcode/login/", "/qrcode/../login/"), "$qr\n", qr.replace("https://", "https:\\\\"),
                qr.replace("A".repeat(32), "%41".repeat(32)))
            attacks.forEach { check(WebLoginCode.parse(it) == null) { "hostile QR accepted" } }
            check(WebLoginCode.parse(WebLoginCode.parse(qr)!!.url().toString()) != null)
            passed("only_official_web_qr_canonicalized_and_hostile_urls_rejected")
            val bytes = shell("cat /sdcard/Download/synthetic-web-qr.png")
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            val decoder = WebLoginQrDecoder()
            fun source(image: android.graphics.Bitmap): RGBLuminanceSource {
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                return RGBLuminanceSource(image.width, image.height, pixels)
            }
            check(WebLoginCode.parse(checkNotNull(decoder.decode(source(bitmap)))) != null)
            val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 320, 320, true)
            for (degrees in listOf(0f, 90f, 180f, 270f)) {
                val rotated = android.graphics.Bitmap.createBitmap(small, 0, 0, small.width, small.height,
                    android.graphics.Matrix().apply { postRotate(degrees) }, true)
                check(WebLoginCode.parse(checkNotNull(decoder.decode(source(rotated)))) != null) { "QR rotation $degrees" }
                if (rotated !== small) rotated.recycle()
            }
            val grey = source(small).matrix
            val frameWidth = small.width + 12; val frameHeight = small.height + 6
            for (stride in listOf(1, 2)) {
                val row = frameWidth * stride + 13; val base = 7
                val buffer = java.nio.ByteBuffer.allocate(base + row * frameHeight)
                for (y in 0 until small.height) for (x in 0 until small.width)
                    buffer.put(base + (y + 3) * row + (x + 5) * stride, grey[y * small.width + x])
                buffer.position(base)
                val frame = WebLoginQrDecoder.luminance(frameWidth, frameHeight, row, stride, buffer,
                    android.graphics.Rect(5, 3, 5 + small.width, 3 + small.height))
                check(WebLoginCode.parse(checkNotNull(decoder.decode(frame))) != null)
                check(buffer.position() == base)
            }
            val blank = RGBLuminanceSource(320, 320, IntArray(320 * 320) { android.graphics.Color.WHITE })
            check(decoder.decode(blank) == null)
            val barcode = com.google.zxing.oned.Code128Writer().encode("123456789012", com.google.zxing.BarcodeFormat.CODE_128, 320, 160)
            val barcodePixels = IntArray(320 * 160) { i -> if (barcode[i % 320, i / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            check(decoder.decode(RGBLuminanceSource(320, 160, barcodePixels)) == null)
            check(WebLoginCode.parse(checkNotNull(decoder.decode(source(small)))) != null)
            small.recycle(); bitmap.recycle()
            passed("qr_only_decoder_offline_rotation_scale_padded_cropped_y_frames_and_miss_recovery")
            val calls = AtomicInteger()
            var responseCode = 200
            var body = "{\"ret\":0}"
            var fail = false
            var entered: CountDownLatch? = null
            var release: CountDownLatch? = null
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                val request = chain.request()
                check(request.method == "POST" && WebLoginCode.parse(request.url.toString()) != null && request.body!!.isOneShot())
                check(request.header("Cookie")!!.contains("1&_token=${account.lease()!!.session.uid}&synthetic-web-login-token;"))
                check(request.header("Cookie2") == "\$version=1")
                val content = Buffer(); request.body!!.writeTo(content); check(content.readUtf8() == "null")
                entered?.countDown(); release?.let { check(it.await(10, TimeUnit.SECONDS)) }
                if (fail) throw IOException("synthetic timeout")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode).message("fixture").body(body.toResponseBody()).build()
            }.build()
            val api = WebLoginApi(targetContext, account, http)
            val flags = WebLoginApi::class.java.getDeclaredField("http").apply { isAccessible = true }.get(api) as OkHttpClient
            check(!flags.followRedirects && !flags.followSslRedirects && !flags.retryOnConnectionFailure)
            flow = WebLoginFlow(account, api)
            val transaction = flow!!
            runOnMainSync { transaction.start(); transaction.scanned(qr, transaction.state.value.epoch) }
            check(transaction.state.value.stage == WebLoginStage.CONFIRM && calls.get() == 0)
            runOnMainSync { transaction.reset(); transaction.confirm() }; check(calls.get() == 0)
            runOnMainSync { transaction.scanned(qr, transaction.state.value.epoch); transaction.stop(); transaction.confirm(); transaction.start() }; check(calls.get() == 0)
            passed("scan_cancel_and_background_never_send_authorization")
            runOnMainSync { transaction.scanned(qr, transaction.state.value.epoch); transaction.confirm(); transaction.confirm() }
            waitFor("confirmed success") { transaction.state.value.stage == WebLoginStage.SUCCESS }
            check(calls.get() == 1)
            passed("explicit_confirm_posts_original_contract_once_and_accepts_ret_zero")
            for ((status, payload) in listOf(200 to "{\"ret\":50}", 401 to "{}", 403 to "{}", 302 to "{}", 200 to "{}", 200 to "{\"ret\":\"0\"}", 200 to "{", 200 to "{\"ret\":1}")) {
                responseCode = status; body = payload
                runOnMainSync { transaction.reset(); transaction.scanned(qr, transaction.state.value.epoch); transaction.confirm() }
                waitFor("failure is visible") { transaction.state.value.stage == WebLoginStage.FAILURE }
                if (status in listOf(401, 403) || payload.contains("50")) check(transaction.state.value.notice.contains("登录状态"))
            }
            fail = true
            runOnMainSync { transaction.reset(); transaction.scanned(qr, transaction.state.value.epoch); transaction.confirm() }
            waitFor("timeout feedback") { transaction.state.value.stage == WebLoginStage.FAILURE }
            check(transaction.state.value.notice.contains("网页状态"))
            fail = false; responseCode = 200; body = "{\"ret\":0}"
            runOnMainSync { transaction.reset(); transaction.scanned(qr, transaction.state.value.epoch); transaction.confirm() }
            waitFor("next scan recovers") { transaction.state.value.stage == WebLoginStage.SUCCESS }
            passed("server_login_expiry_redirect_format_and_timeout_failures_recover_with_fresh_scan")
            val before = calls.get()
            runOnMainSync { transaction.reset(); transaction.scanned(qr, transaction.state.value.epoch); account.logout(); transaction.accountChanged(); transaction.confirm() }
            check(calls.get() == before && transaction.state.value.stage == WebLoginStage.FAILURE)
            runOnMainSync { transaction.reset() }; check(transaction.state.value.stage == WebLoginStage.LOGIN_REQUIRED)
            gateway.uid = 9102; account.login("synthetic", "synthetic"); waitFor("new fixture account") { account.state.value.signedIn && !account.state.value.busy }
            runOnMainSync { transaction.accountChanged() }
            check(transaction.state.value.stage == WebLoginStage.SCANNING)
            passed("restored_account_makes_active_logged_out_scanner_ready_without_relogin")
            entered = CountDownLatch(1); release = CountDownLatch(1)
            runOnMainSync { transaction.reset(); transaction.scanned(qr, transaction.state.value.epoch); transaction.confirm() }
            check(entered!!.await(5, TimeUnit.SECONDS))
            runOnMainSync { transaction.stop(); transaction.start() }; release!!.countDown()
            waitFor("new scan usable after old response") { transaction.state.value.stage == WebLoginStage.SCANNING }
            entered = null; release = null
            passed("changed_account_and_disposed_screen_reject_old_confirmation_and_completion")
            runOnMainSync { transaction.close() }; flow = null
            targetContext.getSharedPreferences("scanner-permission", Context.MODE_PRIVATE).edit().clear().commit()
            check(targetContext.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            activity = openScanner()
            fun denyCamera() {
                waitFor("native camera permission dialog") { listOf("Don’t allow", "Don't allow", "不允许").any { nodes(it).isNotEmpty() } }
                click(listOf("Don’t allow", "Don't allow", "不允许").first { nodes(it).isNotEmpty() })
            }
            denyCamera()
            waitFor("denial stays in scanner") { nodes("允许使用相机").isNotEmpty() }
            check(nodes("打开相机权限").isEmpty() && nodes("从相册选择").isEmpty())
            check(activity!!.javaClass == WebLoginActivity::class.java && !activity!!.isFinishing)
            passed("first_entry_requests_camera_directly_and_denial_stays_on_scanner")
            val recreation = addMonitor(WebLoginActivity::class.java.name, null, false)
            try {
                runOnMainSync { activity!!.recreate() }
                activity = checkNotNull(recreation.waitForActivityWithTimeout(10_000))
                runOnMainSync { activity!!.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            } finally { removeMonitor(recreation) }
            waitFor("denied permission state survives recreation") { nodes("允许使用相机").isNotEmpty() }
            check(listOf("While using the app", "Don’t allow", "Don't allow").all { nodes(it).isEmpty() })
            passed("recreation_after_denial_does_not_repeat_permission_request")
            click("允许使用相机"); denyCamera()
            waitFor("permanent denial recovery") { nodes("打开相机权限").isNotEmpty() }
            check(nodes("扫一扫").isNotEmpty() && nodes("从相册选择").isEmpty())
            passed("permanent_denial_requires_explicit_settings_action_without_auto_redirect")
            click("打开相机权限")
            waitFor("system app settings") { listOf("App info", "应用信息", "Permissions", "权限").any { nodes(it).isNotEmpty() } }
            uiAutomation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.CAMERA)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("CameraX reads QR after returning from settings", 30_000) { nodes("确认登录").isNotEmpty() }
            check(nodes("使用当前账号（ID：9102）登录").isNotEmpty())
            check(nodes("从相册选择").isEmpty())
            val beforeCamera = calls.get()
            click("取消"); waitFor("cancel closes scanner") { activity!!.isFinishing || activity!!.isDestroyed }
            check(calls.get() == beforeCamera)
            passed("settings_grant_return_starts_real_camerax_and_cancel_sends_zero_requests")
            activity = openScanner()
            waitFor("granted camera opens without another permission or settings step") { nodes("确认登录").isNotEmpty() }
            check(nodes("打开相机权限").isEmpty() && nodes("允许使用相机").isEmpty())
            val nextFlow = WebLoginActivity::class.java.getDeclaredField("flow").apply { isAccessible = true }.get(activity) as WebLoginFlow
            WebLoginFlow::class.java.getDeclaredField("api").apply { isAccessible = true }.set(nextFlow, api)
            click("确认登录"); waitFor("success UI") { nodes("已确认登录").isNotEmpty() && nodes("完成").isNotEmpty() }
            click("完成"); activity = null
            passed("already_granted_camera_opens_directly_and_requires_confirmation")
            account.logout()
            val mainMonitor = addMonitor(MainActivity::class.java.name, null, false)
            try {
                runOnMainSync { targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                mainActivity = checkNotNull(mainMonitor.waitForActivityWithTimeout(10_000))
            } finally { removeMonitor(mainMonitor) }
            click("我的")
            waitFor("My toolbar scan action") { nodes("扫一扫").isNotEmpty() && nodes("设置").isNotEmpty() }
            val scannerMonitor = addMonitor(WebLoginActivity::class.java.name, null, false)
            try { click("扫一扫"); activity = checkNotNull(scannerMonitor.waitForActivityWithTimeout(10_000)) } finally { removeMonitor(scannerMonitor) }
            waitFor("toolbar opens scanner directly") { nodes("扫一扫").isNotEmpty() && nodes("去登录").isNotEmpty() }
            check(nodes("账号与同步").isEmpty() && nodes("从相册选择").isEmpty())
            passed("my_toolbar_opens_scanner_in_one_action_without_settings_detour")
            click("去登录"); waitFor("existing SMS login UI") { nodes("手机号").isNotEmpty() && nodes("短信验证码").isNotEmpty() }
            check(nodes("扫码登录网页端").isEmpty())
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("return logged out scanner") { nodes("去登录").isNotEmpty() }
            passed("signed_out_scanner_uses_unchanged_sms_login_and_returns_safely")
            result.putString("result", "PASS ${completed.size} checks: ${completed.joinToString()}")
            finish(-1, result)
        } catch (failure: Throwable) {
            val labels = mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo?) { if (node == null) return; node.text?.let { labels.add(it.toString()) }; node.contentDescription?.let { labels.add(it.toString()) }; repeat(node.childCount) { collect(node.getChild(it)) } }
            collect(uiAutomation.rootInActiveWindow)
            java.io.File(targetContext.cacheDir, "web-login-test-tree.txt").writeText(labels.joinToString("\n"))
            uiAutomation.takeScreenshot()?.let { image -> java.io.FileOutputStream(java.io.File(targetContext.cacheDir, "web-login-test-failure.png")).use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
            result.putString("failure", failure.stackTraceToString() + "\nVisible labels: " + labels.joinToString(" | "))
            finish(0, result)
        }
        finally { activity?.let { runOnMainSync { it.finish() } }; flow?.let { runOnMainSync { it.close() } }; mainActivity?.let { runOnMainSync { it.finish() } }; singleton.set(null, previous); scope.cancel() }
    }
}
