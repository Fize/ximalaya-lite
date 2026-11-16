package com.shixi.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun PlayerAction(icon: AppIcon, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false, count: Long? = null, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.semantics { if (count != null) stateDescription = "${count}条评论" }.heightIn(min = 60.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box {
            CompositionLocalProvider(LocalContentColor provides if (enabled) color else color.copy(alpha = 0.38f)) { AppSymbol(icon) }
            if (count != null) Badge(modifier = Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-4).dp)) { Text(if (count >= 10000) "${count / 10000}万+" else count.toString()) }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = if (enabled) color else color.copy(alpha = 0.38f))
    }
}

@Composable
internal fun SkipHeadTailPanel(albumId: Long, store: SkipHeadTailStore, saved: (Boolean) -> Unit) {
    val original = remember(albumId) { store.get(albumId) }
    var head by remember(albumId) { mutableIntStateOf(original.headSeconds) }
    var tail by remember(albumId) { mutableIntStateOf(original.tailSeconds) }
    Text("对本作品的所有单集生效，设置保存在本机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    @Composable fun SecondsControl(label: String, value: Int, change: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$label：$value 秒", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { change((value - 1).coerceAtLeast(0)) }, enabled = value > 0,
                modifier = Modifier.semantics { contentDescription = "减少${label}秒数" }) { Text("−") }
            TextButton(onClick = { change((value + 1).coerceAtMost(600)) }, enabled = value < 600,
                modifier = Modifier.semantics { contentDescription = "增加${label}秒数" }) { Text("＋") }
        }
        Slider(value = value.toFloat(), onValueChange = { change(it.roundToInt().coerceIn(0, 600)) },
            valueRange = 0f..600f, modifier = Modifier.semantics { contentDescription = "${label}时长"; stateDescription = "$value 秒" })
    }
    SecondsControl("片头", head) { head = it }
    SecondsControl("片尾", tail) { tail = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { head = 0; tail = 0 }) { Text("重置") }
        Button(onClick = { saved(runCatching { store.save(albumId, SkipHeadTail(head, tail)) }.getOrDefault(false)) }) { Text("保存") }
    }
}

@Composable
internal fun SleepTimerPanel(setTimer: (Int) -> Unit) {
    var custom by remember { mutableStateOf("") }
    val minutes = custom.toIntOrNull()
    listOf(0, 1, 15, 30, 60).forEach { value ->
        TextButton(onClick = { setTimer(value) }) { Text(if (value == 0) "关闭定时" else "${value}分钟后暂停") }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("自定义时长", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = custom, onValueChange = { text ->
            if (text.length <= 3 && text.all { it in '0'..'9' }) custom = text
        }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("分钟") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = custom.isNotEmpty() && minutes !in 1..120,
            supportingText = { Text("输入 1–120 分钟") })
        TextButton(enabled = minutes != null && minutes in 1..120, onClick = { minutes?.let(setTimer) }) { Text("确定") }
    }
}
