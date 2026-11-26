package com.shixi.client

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Membership families exposed by the original client's "我的" page payload. */
data class Membership(val name: String, val expiresAt: Long, val active: Boolean)

sealed interface MembershipStatus {
    data object SignedOut : MembershipStatus
    data object Loading : MembershipStatus
    data object None : MembershipStatus
    data object Unavailable : MembershipStatus
    data class Known(val membership: Membership) : MembershipStatus
}

/**
 * Reads VIP/SVIP expiry from `mobile-user/v2/homePage`, the same payload the original
 * client uses for the "我的" page. A verified account lease owns every request.
 */
class MembershipApi(private val context: Context,
    private val accounts: AccountManager = AccountManager.get(context),
    private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()) {
    private val device = context.getSharedPreferences("account-device", Context.MODE_PRIVATE).let {
        it.getString("id", null) ?: UUID.randomUUID().toString().also { id -> check(it.edit().putString("id", id).commit()) }
    }

    /** Returns null when the server confirms the account has no membership family. */
    fun read(lease: AccountLease): Membership? {
        val uid = lease.session.uid
        val token = lease.session.token
        require(uid > 0 && token.matches(Regex("[A-Za-z0-9._~+/%=-]{1,4096}")))
        if (!accounts.current(lease)) throw IOException("账号会话已改变")
        val cookie = "1&_device=android&$device&9.4.74.3;impl=${context.packageName};osversion=${Build.VERSION.SDK_INT};1&_token=$uid&$token;"
        val request = Request.Builder().url("https://mobile.ximalaya.com/mobile-user/v2/homePage")
            .header("Cookie", cookie).header("Cookie2", "\$version=1")
            .header("User-Agent", "ting_9.4.74.3(Android${Build.VERSION.SDK_INT})").header("Accept", "*/*").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("会员信息 HTTP ${response.code}")
            val payload = try { JSONObject(response.body.string()) }
                catch (_: Exception) { throw IOException("会员信息响应格式不符") }
            // Expiry is account-bound and must not leak into a replacement session.
            if (!accounts.current(lease)) throw IOException("账号会话已改变")
            return parseMembership(payload)
        }
    }
}

private val membershipFamilies = listOf(
    "svipInfo" to "SVIP",
    "platinumVipInfo" to "白金会员",
    "vipInfo" to "VIP",
    "childSVipInfo" to "儿童SVIP",
)

private fun membershipRank(name: String): Int = when (name) {
    "SVIP" -> 4
    "白金会员" -> 3
    "VIP" -> 2
    else -> 1
}

/** Timestamps outside 2000–2200 are treated as missing rather than rendered as a date. */
internal const val MEMBERSHIP_MIN_EXPIRY = 946684800000L
internal const val MEMBERSHIP_MAX_EXPIRY = 7258118400000L
internal fun isPlausibleExpiry(value: Long): Boolean = value in MEMBERSHIP_MIN_EXPIRY until MEMBERSHIP_MAX_EXPIRY

private fun Any?.asLongOrZero(): Long = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: 0L
    else -> 0L
}

private fun asObject(value: Any?): JSONObject? = when (value) {
    is JSONObject -> value
    is String -> runCatching { JSONObject(value) }.getOrNull()
    else -> null
}

private fun homePageData(payload: JSONObject): JSONObject? {
    // The original client accepts both `code` and `ret` success envelopes.
    val code = if (payload.has("ret")) payload.optInt("ret", -1) else payload.optInt("code", -1)
    if (code != 0) return null
    return asObject(payload.opt("data"))
}

/**
 * Picks the highest active family; when every family has lapsed it returns the one
 * that expired most recently so the user still sees the past date.
 */
internal fun parseMembership(payload: JSONObject, now: Long = System.currentTimeMillis()): Membership? {
    val data = homePageData(payload) ?: return null
    val vipExpireTime = data.opt("vipExpireTime").asLongOrZero()
    val candidates = membershipFamilies.mapNotNull { (key, name) ->
        val info = asObject(data.opt(key)) ?: return@mapNotNull null
        val infoExpiry = info.opt("expireTime").asLongOrZero()
        val expiresAt = if (key == "vipInfo" && infoExpiry <= 0) vipExpireTime else infoExpiry
        val active = info.optBoolean("isVip", false) && (!isPlausibleExpiry(expiresAt) || expiresAt > now)
        Membership(name, expiresAt, active)
    }
    val all = if (candidates.any { it.name == "VIP" } || vipExpireTime <= 0) candidates
        else candidates + Membership("VIP", vipExpireTime, vipExpireTime > now)
    return all.filter { it.active }.maxByOrNull { membershipRank(it.name) }
        ?: all.filter { !it.active && it.expiresAt in 1..now }.maxByOrNull { it.expiresAt }
}
