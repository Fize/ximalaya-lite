package com.shixi.client

/** Presentation only: account protocol/status contracts stay unchanged. */
internal fun accountStatusText(status: String): String = when (status) {
    "已登录（服务端确认）" -> "已登录"
    "登录状态待确认", "未确认登录", "上次已确认登录，本次校验失败" -> "登录状态待更新"
    "恢复登录状态", "校验登录状态", "刷新登录状态" -> "正在更新登录状态…"
    "登录并校验状态" -> "正在登录…"
    "发送验证码" -> "正在发送验证码…"
    "等待输入验证码" -> "请输入验证码"
    "未登录", "登录已失效", "退出未完成" -> status
    else -> "请稍候…"
}

internal fun accountFeedback(result: String): String = when {
    result == "登录状态校验通过。" -> "登录成功"
    result == "刷新并校验通过。" -> "登录状态已更新"
    result == "服务端已接受短信请求，请查看手机短信。" -> "验证码已发送，请查看短信"
    result == "已退出此客户端并清除本机账号凭据。" -> "已退出登录"
    result.contains("需要官方额外验证") -> "请先在喜马拉雅 App 完成账号验证，再重试"
    result.contains("需 ARM64") -> "此设备暂不支持账号登录"
    result.contains("重试退出") -> "退出失败，请重试"
    result.contains("请重新") || result.contains("请先登录") -> "登录已失效，请重新登录"
    result.contains("倒计时") -> "请稍后再获取验证码"
    result.contains("4至8位短信验证码") -> "请输入 4 至 8 位验证码"
    result.contains("校验验证码") && result.contains("服务端拒绝") -> "验证码验证失败，请检查后重试"
    result.contains("发送验证码") && result.contains("服务端拒绝") -> "验证码发送失败，请稍后重试"
    result.contains("11位手机号") -> "请输入正确的手机号"
    result.contains("超时") -> "连接超时，请重试"
    result.contains("网络") || result.contains("连接失败") -> "连接失败，请检查网络后重试"
    else -> "操作未成功，请稍后重试"
}
