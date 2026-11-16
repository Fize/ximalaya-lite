package com.shixi.client

import android.content.Context

data class SkipHeadTail(val headSeconds: Int = 0, val tailSeconds: Int = 0)

/** These settings belong to a work, and remain on this device. */
class SkipHeadTailStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("skip-head-tail", Context.MODE_PRIVATE)
    fun get(albumId: Long): SkipHeadTail {
        if (albumId <= 0) return SkipHeadTail()
        return SkipHeadTail(prefs.getInt("$albumId:head", 0), prefs.getInt("$albumId:tail", 0))
    }
    fun save(albumId: Long, value: SkipHeadTail): Boolean {
        require(albumId > 0 && value.headSeconds in 0..600 && value.tailSeconds in 0..600)
        return prefs.edit().putInt("$albumId:head", value.headSeconds).putInt("$albumId:tail", value.tailSeconds).commit()
    }
}

/** Called on the playback thread; each automatic seek is performed at most once per episode. */
class SkipHeadTailPolicy {
    private var headHandled = false
    private var tailHandled = false
    fun reset() { headHandled = false; tailHandled = false }
    fun target(position: Long, duration: Long, value: SkipHeadTail): Long? {
        if (duration <= 0 || position < 0) return null
        val head = value.headSeconds * 1000L
        val tail = value.tailSeconds * 1000L
        if (head + tail >= duration) return null
        if (!headHandled) {
            headHandled = true
            if (head > 0 && position < 1500L) return head
        }
        if (!tailHandled && tail > 0 && position >= duration - tail && position < duration) {
            tailHandled = true
            return duration
        }
        return null
    }
}
