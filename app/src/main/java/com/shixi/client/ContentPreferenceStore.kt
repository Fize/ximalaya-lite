package com.shixi.client

import android.content.Context
import android.os.Build

/**
 * Content preferences. The recommendation switch controls whether the
 * discovery request carries the account identity at all; it does not touch
 * listening-history sync, which has its own account flow.
 */
class ContentPreferenceStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("content-preferences", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    companion object {
        @Volatile private var instance: ContentPreferenceStore? = null
        fun get(context: Context): ContentPreferenceStore = instance ?: synchronized(this) {
            instance ?: ContentPreferenceStore(context).also { instance = it }
        }
    }

    /** Defaults to on, matching the approved wireframe decision. */
    var personalized: Boolean
        get() = prefs.getBoolean("personalized", true)
        set(value) {
            prefs.edit().putBoolean("personalized", value).apply()
        }

    /**
     * Cookie for the recommendation endpoints, or null for anonymous requests.
     * The official APIs expose no opt-out flag, so "off" is implemented as an
     * anonymous request. The effect of this cookie on recommendation content is
     * not verified without a test account (see api-spec.md §9).
     */
    fun recommendCookie(context: Context = appContext): String? {
        if (!personalized) return null
        val lease = AccountManager.get(context).lease() ?: return null
        val session = lease.session
        if (session.uid <= 0 || session.token.isBlank()) return null
        return buildString {
            append("1&_device=android&").append(AccountContentApi.deviceId(context)).append("&9.4.74.3;")
            append("channel=shixi;impl=").append(context.packageName).append(";osversion=").append(Build.VERSION.SDK_INT).append(';')
            append("1&_token=").append(session.uid).append('&').append(session.token).append(';')
        }
    }
}
