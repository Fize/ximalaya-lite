package com.shixi.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.IOException

internal enum class WebLoginStage { SCANNING, CONFIRM, SENDING, SUCCESS, FAILURE, LOGIN_REQUIRED }
internal data class WebLoginState(val stage: WebLoginStage = WebLoginStage.LOGIN_REQUIRED, val epoch: Int = 0, val uid: Long = 0, val notice: String = "")

/** Ephemeral screen transaction: neither challenge nor credentials enter saved state. */
internal class WebLoginFlow(private val account: AccountManager, private val api: WebLoginApi) {
    private class Confirmation(val code: WebLoginCode, val lease: AccountLease)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(WebLoginState())
    val state = mutable.asStateFlow()
    @Volatile private var active = false
    @Volatile private var pending: Confirmation? = null
    private var call: Call? = null

    fun start() { active = true; reset() }
    fun stop() { active = false; clear(); mutable.value = WebLoginState(epoch = mutable.value.epoch + 1) }
    fun close() { stop(); scope.cancel() }
    private fun clear() { pending = null; call?.cancel(); call = null }
    fun reset() {
        clear()
        mutable.value = WebLoginState(if (account.lease() == null) WebLoginStage.LOGIN_REQUIRED else WebLoginStage.SCANNING, mutable.value.epoch + 1)
    }
    fun accountChanged() {
        if (!active) return
        val confirmation = pending
        if (confirmation != null && !account.current(confirmation.lease)) {
            clear(); mutable.value = WebLoginState(WebLoginStage.FAILURE, mutable.value.epoch + 1, notice = "登录状态已变化，请重新扫描")
        } else if (account.lease() == null && mutable.value.stage == WebLoginStage.SCANNING) {
            clear(); mutable.value = WebLoginState(WebLoginStage.LOGIN_REQUIRED, mutable.value.epoch + 1)
        } else if (account.lease() != null && mutable.value.stage == WebLoginStage.LOGIN_REQUIRED) {
            reset()
        }
    }
    fun scanned(raw: String, epoch: Int) {
        if (!active || mutable.value.stage != WebLoginStage.SCANNING || mutable.value.epoch != epoch) return
        val code = WebLoginCode.parse(raw)
        if (code == null) { failed("请扫描喜马拉雅网页版的登录二维码"); return }
        val lease = account.lease()
        if (lease == null) { reset(); return }
        pending = Confirmation(code, lease)
        mutable.value = mutable.value.copy(stage = WebLoginStage.CONFIRM, uid = lease.session.uid)
    }
    fun failed(notice: String) {
        if (!active || mutable.value.stage != WebLoginStage.SCANNING) return
        clear(); mutable.value = WebLoginState(WebLoginStage.FAILURE, mutable.value.epoch + 1, notice = notice)
    }
    fun confirm() {
        val confirmation = pending ?: return
        if (!active || mutable.value.stage != WebLoginStage.CONFIRM) return
        if (!account.current(confirmation.lease)) { accountChanged(); return }
        mutable.value = mutable.value.copy(stage = WebLoginStage.SENDING)
        val request = api.call(confirmation.code, confirmation.lease) {
            active && pending === confirmation && mutable.value.stage == WebLoginStage.SENDING
        }
        call = request
        scope.launch {
            var notice: String? = null
            try { withContext(Dispatchers.IO) { api.accept(request.execute()) } }
            catch (failure: WebLoginFailure) { notice = failure.notice }
            catch (_: IOException) { notice = "暂时无法确认登录，请查看网页状态后重新扫描" }
            catch (_: Exception) { notice = "未能确认登录，请刷新网页二维码后重新扫描" }
            if (!active || pending !== confirmation) return@launch
            if (!account.current(confirmation.lease)) { accountChanged(); return@launch }
            clear()
            mutable.value = WebLoginState(if (notice == null) WebLoginStage.SUCCESS else WebLoginStage.FAILURE,
                mutable.value.epoch + 1, notice = notice.orEmpty())
        }
    }
}
