package com.shixi.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class WebLoginActivity : ComponentActivity() {
    private lateinit var flow: WebLoginFlow
    private var cameraGranted by mutableStateOf(false)
    private var cameraBlocked by mutableStateOf(false)
    private var requestedThisVisit = false
    private val permissionHistory by lazy { getSharedPreferences("scanner-permission", MODE_PRIVATE) }

    private fun updateCameraPermission() {
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        cameraBlocked = !cameraGranted && permissionHistory.getBoolean("camera-requested", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedThisVisit = savedInstanceState?.getBoolean("camera-requested-this-visit") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val account = AccountManager.get(this)
        flow = WebLoginFlow(account, WebLoginApi(this, account))
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect { androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
            MaterialTheme(colorScheme = clientColors(dark), typography = ClientTypography) {
                val state by flow.state.collectAsState()
                val accountState by account.state.collectAsState()
                LaunchedEffect(accountState) { flow.accountChanged() }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { updateCameraPermission() }
                fun requestCamera() {
                    requestedThisVisit = true
                    permissionHistory.edit().putBoolean("camera-requested", true).apply()
                    permission.launch(Manifest.permission.CAMERA)
                }
                LaunchedEffect(state.stage, cameraGranted, cameraBlocked) {
                    if (state.stage == WebLoginStage.SCANNING && !cameraGranted && !cameraBlocked && !requestedThisVisit) requestCamera()
                }
                BackHandler { finish() }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SymbolButton(AppIcon.Back, "返回", onClick = { finish() })
                            Text("扫一扫", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        }
                        if (state.stage == WebLoginStage.SCANNING) {
                            if (cameraGranted) {
                                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                    key(state.epoch) {
                                        WebLoginCamera(Modifier.fillMaxSize(), { flow.scanned(it, state.epoch) }, { flow.failed("相机暂时不可用，请重试") })
                                    }
                                    Box(Modifier.fillMaxWidth(0.8f).widthIn(max = 280.dp).aspectRatio(1f).border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(16.dp)))
                                }
                                Text("对准喜马拉雅网页上的登录二维码", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                                Spacer(Modifier.height(16.dp))
                            } else {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Spacer(Modifier.height(48.dp))
                                    AppSymbol(AppIcon.Scan, modifier = Modifier.size(48.dp))
                                    Text("需要使用相机扫描二维码", style = MaterialTheme.typography.titleMedium)
                                    Text(if (cameraBlocked) "请在系统设置中允许使用相机，返回后即可扫描。" else "允许相机权限后，即可扫描网页登录二维码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (cameraBlocked) Button(onClick = {
                                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                                    }) { Text("打开相机权限") }
                                    else Button(onClick = { requestCamera() }) { Text("允许使用相机") }
                                }
                            }
                        } else {
                            val scroll = rememberScrollState()
                            LaunchedEffect(state.stage) { scroll.scrollTo(0) }
                            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                when (state.stage) {
                                    WebLoginStage.LOGIN_REQUIRED -> {
                                        Text("登录后使用扫一扫", style = MaterialTheme.typography.headlineSmall)
                                        Text("使用当前账号登录喜马拉雅网页版。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(onClick = { startActivity(Intent(this@WebLoginActivity, AccountActivity::class.java)) }) { Text("去登录") }
                                    }
                                    WebLoginStage.CONFIRM, WebLoginStage.SENDING -> {
                                        AppSymbol(AppIcon.Person, modifier = Modifier.size(48.dp))
                                        Text("确认登录喜马拉雅网页版", style = MaterialTheme.typography.headlineSmall)
                                        Text("使用当前账号（ID：${state.uid}）登录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("请确认二维码来自你正在使用的电脑。")
                                        if (state.stage == WebLoginStage.SENDING) LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Button(onClick = { flow.confirm() }, enabled = state.stage == WebLoginStage.CONFIRM, modifier = Modifier.fillMaxWidth()) { Text(if (state.stage == WebLoginStage.SENDING) "正在确认…" else "确认登录") }
                                        if (state.stage == WebLoginStage.CONFIRM) TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                                    }
                                    WebLoginStage.SUCCESS -> {
                                        Text("已确认登录", style = MaterialTheme.typography.headlineSmall)
                                        Text("请回到电脑继续使用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(onClick = { finish() }) { Text("完成") }
                                    }
                                    WebLoginStage.FAILURE -> {
                                        Text("未完成登录", style = MaterialTheme.typography.headlineSmall)
                                        Text(state.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(onClick = { flow.reset() }) { Text("重新扫描") }
                                        TextButton(onClick = { startActivity(Intent(this@WebLoginActivity, AccountActivity::class.java)) }) { Text("查看登录状态") }
                                    }
                                    WebLoginStage.SCANNING -> Unit
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onStart() {
        flow.start()
        updateCameraPermission()
        super.onStart()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("camera-requested-this-visit", requestedThisVisit)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { flow.stop(); super.onStop() }
    override fun onDestroy() { flow.close(); super.onDestroy() }
}
