package com.shixi.client

import android.content.Context

/**
 * Remembers which app version the login notice was accepted for.
 * Logging out does not clear it; a version bump asks again.
 */
class LegalNoticeStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("legal-notice", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var instance: LegalNoticeStore? = null
        fun get(context: Context): LegalNoticeStore = instance ?: synchronized(this) {
            instance ?: LegalNoticeStore(context).also { instance = it }
        }
    }

    fun accepted(version: String): Boolean =
        version.isNotBlank() && prefs.getString("login-consent-version", null) == version

    fun accept(version: String) {
        prefs.edit().putString("login-consent-version", version).apply()
    }
}
