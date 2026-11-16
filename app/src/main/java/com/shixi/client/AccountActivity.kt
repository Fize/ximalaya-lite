package com.shixi.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class AccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = AccountManager.get(this)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            SideEffect { androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !darkTheme; isAppearanceLightNavigationBars = !darkTheme } }
            MaterialTheme(colorScheme = clientColors(darkTheme), typography = ClientTypography) {
                val state by account.state.collectAsState()
                // Deliberately not saveable: phone/OTP never enter saved instance state or disk.
                var mobile by remember { mutableStateOf("") }
                var code by remember { mutableStateOf("") }
                var seconds by remember { mutableIntStateOf(account.remainingSeconds()) }
                LaunchedEffect(Unit) { while (true) { seconds = account.remainingSeconds(); delay(250) } }
                val feedback = remember { SnackbarHostState() }
                var requested by remember { mutableStateOf(false) }
                var confirmLogout by remember { mutableStateOf(false) }
                LaunchedEffect(state.busy, state.result) {
                    if (requested && !state.busy && state.result.isNotBlank()) { requested = false; feedback.showSnackbar(accountFeedback(state.result), withDismissAction = true) }
                }
                if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出登录？") }, text = { Text("本机收听记录会保留。") }, confirmButton = { TextButton(onClick = { confirmLogout = false; requested = true; mobile = ""; code = ""; account.logout() }) { Text("退出登录") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
                Scaffold(snackbarHost = { SnackbarHost(feedback) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            SymbolButton(AppIcon.Back, "返回", onClick = { finish() })
                            Text("账号与同步", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                        }
                        Text(if (state.signedIn && !state.busy) "已登录" else accountStatusText(state.status), style = MaterialTheme.typography.titleMedium)
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!state.signedIn) {
                            Text("使用喜马拉雅账号登录")
                            OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(11); code = "" }, label = { Text("手机号") }, singleLine = true,
                                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                            Button(onClick = { requested = true; account.send(mobile); seconds = account.remainingSeconds() }, enabled = !state.busy && seconds == 0 && mobile.matches(Regex("1[3-9][0-9]{9}"))) {
                                Text(if (seconds > 0) "${seconds}秒后可重发" else "获取验证码")
                            }
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("短信验证码") }, singleLine = true,
                                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                            Button(onClick = { requested = true; val entered = code; code = ""; account.login(mobile, entered) }, enabled = !state.busy && mobile.matches(Regex("1[3-9][0-9]{9}")) && code.length in 4..8) { Text("登录") }
                        }
                        if (state.signedIn) OutlinedButton(onClick = { requested = true; account.refresh() }, enabled = !state.busy) { Text("更新登录状态") }
                        if (state.signedIn) TextButton(enabled = !state.busy, onClick = { confirmLogout = true }) { Text("退出登录") }
                        HorizontalDivider()
                        Text("登录后自动同步订阅与收听记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
