package com.shixi.client

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONException

/** Only fixed labels and numeric codes reach the UI; never display raw server bodies or exceptions. */
class CloudReadFailure(val stage: String, cause: Exception) : IOException(cause)
data class SyncProblem(val stage: String, val reason: String) {
    val text get() = "$stage$reason"
    companion object {
        fun from(stage: String, error: Exception): SyncProblem {
            val message = (error as? CloudFailure)?.message.orEmpty()
            val location = when {
                message.startsWith("读取账号历史") -> "历史读取"
                message.startsWith("读取账号订阅") -> "订阅读取"
                message.startsWith("同步收听进度") -> "进度上传"
                message.startsWith("同步账号订阅") -> "订阅更新"
                error is CloudReadFailure -> error.stage
                else -> stage
            }
            val cause = if (error is CloudReadFailure) error.cause else error
            val http = Regex("HTTP ([0-9]{3})(?:[^0-9]|$)").find(message)?.groupValues?.get(1)
            val business = Regex("业务拒绝（(-?[0-9]{1,10})）").find(message)?.groupValues?.get(1)
            val reason = when {
                http != null -> "失败（HTTP $http）"
                business != null -> "未通过（$business）"
                cause is SocketTimeoutException -> "超时，请稍后重试"
                cause is UnknownHostException -> "连接失败，请检查网络"
                cause is JSONException || cause is IllegalArgumentException || message.contains("格式") || message.contains("缺少同步结果") -> "失败（数据格式异常）"
                message.contains("签名") -> "暂不可用（进度验证失败）"
                message.contains("超过本次同步范围") -> "暂未完成（记录数量超限）"
                else -> "失败，请稍后重试"
            }
            return SyncProblem(location, reason)
        }
    }
}
