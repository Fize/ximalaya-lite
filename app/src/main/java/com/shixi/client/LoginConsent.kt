package com.shixi.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private data class LoginNotice(val title: String, val body: String)

/** Facts only: the app is unofficial, paid rights stay with the service, credentials stay local. */
private val loginNotices = listOf(
    LoginNotice(
        title = "第三方客户端",
        body = "本应用是第三方开源客户端，由社区开发维护，与喜马拉雅官方不存在隶属、合作或授权关系，也未获官方认可或支持。你可以在官方渠道获取官方 App。",
    ),
    LoginNotice(
        title = "账号与内容",
        body = "登录使用你本人的喜马拉雅账号。会员权益、付费内容与账号规则仍以官方为准；本应用不会绕过付费或会员限制。使用第三方客户端可能带来的账号限制、数据异常等风险由你自行承担。",
    ),
    LoginNotice(
        title = "隐私与数据",
        body = "手机号与验证码仅用于本次登录，不写入日志、不落盘；登录凭证加密保存在本机；订阅与收听记录仅在你开启同步时上传。应用不含广告与统计 SDK。",
    ),
)

/**
 * @param required true when the notice gates login; false for a read-only re-open.
 */
@Composable
fun LoginConsentScreen(required: Boolean, onAccept: () -> Unit, onExit: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SymbolButton(AppIcon.Back, if (required) "不同意并返回" else "返回", onClick = onExit)
            Text(
                if (required) "登录前请阅读" else "登录声明",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Text(
            "使用喜马拉雅账号登录前，请确认以下内容。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        loginNotices.forEach { notice ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(notice.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(notice.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (required) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { checked = !checked }
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = { checked = it })
                Text("我已阅读并同意以上声明", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onAccept, enabled = checked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("同意并继续")
            }
            TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("不同意，返回")
            }
        } else {
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回") }
        }
    }
}
