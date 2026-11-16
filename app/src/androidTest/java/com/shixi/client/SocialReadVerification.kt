package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle

class SocialReadVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out=Bundle()
        try {
            val api=AccountContentApi(targetContext)
            val album=api.albumDetails(3544)
            check(album.id==3544L && album.title.isNotBlank() && album.cover.startsWith("https://"))
            val minglong = api.albumDetails(125160552)
            check(minglong.cover.startsWith("https://") && !minglong.cover.contains("!op_type="))
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            java.net.URL(minglong.cover).openStream().use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            check(opts.outWidth >= 1000 && opts.outHeight >= 1000)
            val comments=api.episodeComments(222612,1)
            check(comments.rows.isNotEmpty() && comments.rows.all { it.id>0 && it.content.isNotBlank() && it.createdAt>0 } && comments.rows.any { it.avatar.startsWith("https://") })
            if (comments.hasMore) { val second=api.episodeComments(222612,2); check(second.rows.isNotEmpty());check(second.rows.none { a -> comments.rows.any { it.id==a.id } }) }
            out.putString("result","PASS live album metadata and public episode comments via account-safe transport; rows=${comments.rows.size}")
            finish(-1,out)
        } catch(t:Throwable) { out.putString("failure",t.stackTraceToString());finish(0,out) }
    }
}
