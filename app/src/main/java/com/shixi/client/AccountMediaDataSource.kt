@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shixi.client

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.shixi.protocol.ContentAccessException
import com.shixi.protocol.ListeningAccess

/** Pins even buffered upstream reads to the identity that opened the source. No credentials here. */
class AccountMediaDataSource(private val upstream: DataSource, private val identity: () -> Any) : DataSource {
    private var owner: Any? = null
    private var firstRead = true
    private fun checkOwner() { if (owner != identity()) throw ContentAccessException(ListeningAccess.UNKNOWN, "账号会话已改变，请重新选择节目") }
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun open(dataSpec: DataSpec): Long {
        owner = identity()
        firstRead = true
        StartupTiming.mark("media_open_start")
        try { val length = upstream.open(dataSpec); StartupTiming.mark("media_open_done"); checkOwner(); return length }
        catch (failure: Exception) { upstream.close(); owner = null; throw failure }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOwner(); val count = upstream.read(buffer, offset, length); checkOwner()
        if (count > 0 && firstRead) { firstRead = false; StartupTiming.mark("media_first_bytes") }
        return count
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { owner = null; upstream.close() }
}
