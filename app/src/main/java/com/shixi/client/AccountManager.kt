package com.shixi.client

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionStore {
    fun read(): AccountSession?
    fun write(session: AccountSession)
    fun clear()
}

class EncryptedSessionStore(context: Context) : SessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "account-session-v1"))
    private val alias = "shixi.account.v1"
    private val aad = "${context.packageName}:account:v1".toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun read(): AccountSession? {
        if (!file.baseFile.exists()) return null
        val envelope = JSONObject(String(file.readFully(), Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        cipher.updateAAD(aad)
        val data = JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        val uid = data.getLong("uid"); val token = data.getString("token")
        check(uid > 0 && token.matches(Regex("[A-Za-z0-9._~+/%=-]{1,4096}")))
        return AccountSession(uid, token)
    }
    @Synchronized override fun write(session: AccountSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        val data = cipher.doFinal(JSONObject().put("uid", session.uid).put("token", session.token).toString().toByteArray())
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP)).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(envelope); file.finishWrite(stream) } catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    @Synchronized override fun clear() { file.delete(); check(!file.baseFile.exists()) }
}

data class AccountState(val signedIn: Boolean = false, val busy: Boolean = false,
    val uid: Long = 0, val generation: Int = 0, val credentialRevision: Int = 0, val status: String = "未登录", val result: String = "手机号和验证码仅用于本次登录，不写入日志。")

/** One process-wide owner survives activity rotation; logout invalidates every in-flight result. */
class AccountLease internal constructor(val generation: Int, val session: AccountSession, internal val playbackLineage: Any) { val owner: String get() = "account:${session.uid}" }

class AccountManager(private val gateway: AccountGateway, private val store: SessionStore,
    private val cooldown: android.content.SharedPreferences, private val boot: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val claimGuestHistory: (String) -> Unit = {}) {
    private val guard = Any()
    private val mutable = MutableStateFlow(AccountState())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var session: AccountSession? = null
    private var playbackLineage = Any()

    init { operation("恢复登录状态") { id ->
        val saved = synchronized(guard) {
            if (generation != id) return@operation
            try { store.read() } catch (_: Exception) {
                store.clear(); throw AccountFailure("恢复登录状态", "本机凭据不可用，请重新登录")
            }
        }
        if (saved == null) update(id) { AccountState(busy = true) }
        else {
            // Keep a recoverable session on transport failure, but never show it as verified.
            update(id) { session = saved; it.copy(status = "登录状态待确认") }
            verify(id, saved, false)
        }
    } }

    private fun update(id: Int, block: (AccountState) -> AccountState) = synchronized(guard) {
        if (generation == id) mutable.value = block(mutable.value)
    }
    private fun operation(label: String, work: (Int) -> Unit) {
        val id = synchronized(guard) {
            if (mutable.value.busy) return
            mutable.value = mutable.value.copy(busy = true, status = label, result = "处理中，请稍候")
            generation
        }
        scope.launch {
            try { work(id) }
            catch (failure: AccountFailure) { update(id) { it.copy(status = if (it.signedIn) "上次已确认登录，本次校验失败" else "未确认登录", result = failure.message.orEmpty()) } }
            catch (_: Exception) { update(id) { it.copy(status = "未确认登录", signedIn = false, result = "$label：本机处理失败，请重试") } }
            finally { update(id) { it.copy(busy = false) } }
        }
    }

    /** Credential identity, independent of whether a re-verification is currently in flight. */
    fun contentIdentity(): Any = synchronized(guard) { generation to session }
    fun lease(): AccountLease? = synchronized(guard) { session?.takeIf { mutable.value.signedIn }?.let { AccountLease(generation, it, playbackLineage) } }
    fun current(lease: AccountLease): Boolean = synchronized(guard) { generation == lease.generation && session === lease.session && mutable.value.signedIn }
    /** Only verified refresh preserves this lineage. Network calls still require exact session identity. */
    fun renewPlayback(lease: AccountLease): AccountLease? = synchronized(guard) {
        session?.takeIf { mutable.value.signedIn && generation == lease.generation &&
            playbackLineage === lease.playbackLineage && it.uid == lease.session.uid }
            ?.let { AccountLease(generation, it, playbackLineage) }
    }
    fun invalidate(lease: AccountLease) = synchronized(guard) { if (current(lease)) { generation++; session = null; runCatching { store.clear() }; mutable.value = AccountState(generation = generation, status = "登录已失效", result = "请重新登录后再同步，待同步记录仍保留在原账号下。") } }

    fun remainingSeconds(): Int {
        if (cooldown.getInt("boot", -1) != boot) return 0
        return ((cooldown.getLong("until", 0) - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceIn(0, 60)
    }
    fun send(mobile: String) = operation("发送验证码") { id ->
        if (!mobile.matches(Regex("1[3-9][0-9]{9}"))) throw AccountFailure("发送验证码", "请输入中国大陆11位手机号")
        // Ownership and cooldown reservation are atomic, including across logout/new requests.
        synchronized(guard) {
            if (generation != id) return@operation
            if (remainingSeconds() > 0) throw AccountFailure("发送验证码", "请等待倒计时结束")
            if (!cooldown.edit().putLong("until", SystemClock.elapsedRealtime() + 60_000).putInt("boot", boot).commit())
                throw AccountFailure("发送验证码", "无法保存发送间隔，请重试")
        }
        gateway.send(mobile)
        update(id) { it.copy(status = "等待输入验证码", result = "服务端已接受短信请求，请查看手机短信。") }
    }
    fun login(mobile: String, code: String) = operation("登录并校验状态") { id ->
        synchronized(guard) {
            if (generation != id) return@operation
            store.clear(); session = null; playbackLineage = Any()
            mutable.value = mutable.value.copy(signedIn = false, uid = 0)
        }
        val candidate = gateway.login(mobile, code)
        verify(id, candidate, true)
    }
    private fun verify(id: Int, candidate: AccountSession, save: Boolean) {
        val valid = gateway.check(candidate)
        update(id) {
            if (valid) {
                // Persist ownership under the session guard before credentials or signed-in state escape.
                claimGuestHistory("account:${candidate.uid}")
                if (save) store.write(candidate)
                session = candidate
                it.copy(signedIn = true, uid = candidate.uid, generation = generation, status = "已登录（服务端确认）", result = "登录状态校验通过。")
            } else {
                store.clear(); session = null
                it.copy(signedIn = false, uid = 0, generation = generation, status = "登录已失效", result = "服务端未确认登录，请重新获取验证码。")
            }
        }
    }
    fun check() = operation("校验登录状态") { id ->
        val current = synchronized(guard) { session } ?: throw AccountFailure("校验登录状态", "尚无可校验的本机账号，请先登录")
        verify(id, current, false)
    }
    fun refresh() = operation("刷新登录状态") { id ->
        val current = synchronized(guard) { session } ?: throw AccountFailure("刷新登录状态", "请先登录")
        val next = gateway.refresh(current)
        if (next.uid != current.uid) throw AccountFailure("刷新登录状态", "账号身份不匹配")
        if (!gateway.check(next)) throw AccountFailure("刷新登录状态", "新凭据未通过校验，原会话未替换")
        update(id) { claimGuestHistory("account:${next.uid}"); store.write(next); session = next; it.copy(credentialRevision = it.credentialRevision + 1, signedIn = true, uid = next.uid, generation = generation, status = "已登录（服务端确认）", result = "刷新并校验通过。") }
    }
    fun logout() = synchronized(guard) {
        val previous = session
        generation++; session = null
        try { store.clear(); mutable.value = AccountState(generation = generation, result = "已退出此客户端并清除本机账号凭据。") }
        catch (_: Exception) { mutable.value = AccountState(generation = generation, status = "退出未完成", result = "本机凭据未能清除，请重试退出。") }
        if (previous != null) scope.launch { runCatching { gateway.logout(previous) } }
    }
    companion object {
        @Volatile private var instance: AccountManager? = null
        fun get(context: Context): AccountManager = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                AccountManager(AccountApi(app), EncryptedSessionStore(app), app.getSharedPreferences("sms-cooldown", Context.MODE_PRIVATE),
                    android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0),
                    claimGuestHistory = GuestHistoryBinding(File(app.noBackupFilesDir, "first-account-history-v1"))::claim).also { instance = it }
            }
        }
    }
}
