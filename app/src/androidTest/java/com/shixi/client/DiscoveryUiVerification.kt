package com.shixi.client

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import com.shixi.protocol.DiscoveryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Drives the real Activity against a fixture-backed transport: five discovery
 * tabs, category drill-down, and the guarantee that ad modules never render.
 */
class DiscoveryUiVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var activity: ComponentActivity? = null
        val field = DiscoveryRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val original = field.get(null)

        fun body(text: String): Response = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://fixture.invalid/").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(text.toResponseBody("application/json".toMediaType())).build()

        val api = DiscoveryApi(transport = { request ->
            val url = request.url.toString()
            when {
                url.contains("explore/v2/getRecommend") -> body("""{"ret":200,"data":{"cards":[
                    {"categoryId":3,"title":"有声书","name":"youshengshu","hotWord":["测试"],"rankTitle":"有声书新品榜","albumList":[
                        {"albumId":101,"albumTitle":"推荐专辑甲","albumCoverPath":"a.jpg","albumUserNickName":"主播甲","albumPlayCount":12000,"albumTrackCount":30,"isPaid":false}]}]}}""")
                url.contains("explore/guessYouLike") -> body("""{"ret":200,"data":{"count":1,"recommendInfoList":[
                    {"albumId":102,"albumTitle":"猜你喜欢乙","albumCoverPath":"b.jpg","albumUserNickName":"主播乙","albumPlayCount":34000,"albumTrackCount":12,"isPaid":true}]}}""")
                url.contains("category/breadcrumbCategoryInfo") -> body("""{"ret":200,"data":{"categories":[
                    {"id":9,"displayName":"历史","pinyin":"lishi"},{"id":2,"displayName":"音乐","pinyin":"yinyue"}]}}""")
                url.contains("category/allCategoryInfo") -> body("""{"ret":200,"data":[
                    {"name":"知识","categories":[{"id":9,"displayName":"历史"},{"id":2,"displayName":"音乐"}]}]}""")
                url.contains("queryCategoryPage") -> body("""{"ret":0,"data":{"categoryId":9,"categoryDisplayName":"历史",
                    "subCategories":[{"subCategoryId":1,"code":"zhengshi","displayValue":"正史"}],
                    "categoryMetaDatas":[{"id":131,"name":"是否完本","metaValues":[{"id":2560,"displayName":"已完本"}]}],
                    "firstPageCategoryAlbums":{"page":1,"totalCount":100,"albumBriefDetailInfos":[
                        {"albumInfo":{"id":201,"title":"分类专辑丙","cover":"c.jpg","albumFinishTag":2,"albumPayType":0},
                         "anchorInfo":{"id":9,"nickname":"主播丙"},"statCountInfo":{"playCount":5000,"trackCount":20}}]}}}""")
                url.contains("queryCategoryAlbumsByPage") -> body("""{"ret":0,"data":{"page":1,"albumBriefDetailInfos":[
                    {"albumInfo":{"id":202,"title":"分类分页丁","cover":"d.jpg","albumFinishTag":2,"albumPayType":0},
                     "anchorInfo":{"id":9,"nickname":"主播丁"},"statCountInfo":{"playCount":6000,"trackCount":21}}]}}""")
                url.contains("rank/queryRankClusterDetail") -> body("""{"ret":0,"data":[
                    {"clusterCode":"hotplay","categoryInfos":[{"title":"热门","categoryCode":"all","rankId":47}]},
                    {"clusterCode":"anchorInfluence","categoryInfos":[{"title":"有声书主播","categoryCode":"voiceActor74","rankId":95}]}]}""")
                url.contains("rank/queryRankCluster") -> body("""{"ret":0,"data":[
                    {"clusterCode":"hotplay","clusterName":"热播榜"},{"clusterCode":"anchorInfluence","clusterName":"主播势力榜"}]}""")
                url.contains("rank/queryRank") -> {
                    if (url.contains("anchorInfluence")) body("""{"ret":0,"data":{"title":"有声书主播榜","rankModuleInfoList":[
                        {"id":301,"userInfo":{"id":301,"nickname":"主播戊","logo":"e.jpg","verifyStatus":3},"statCountInfo":{"followerCount":9999,"albumCount":8}}]}}""")
                    else body("""{"ret":0,"data":{"title":"热播榜","rankModuleInfoList":[
                        {"id":401,"albumInfo":{"id":401,"title":"榜单专辑己","cover":"f.jpg"},"statCountInfo":{"playCount":7000,"trackCount":22}}]}}""")
                }
                url.contains("revision/user/basic") -> body("""{"ret":200,"data":{"uid":301,"nickName":"主播戊","cover":"e.jpg","fansCount":9999,"albumsCount":8,"personalSignature":"简介"}}""")
                url.contains("revision/user?") -> body("""{"ret":200,"data":{"uid":301,"pubPageInfo":{"totalCount":8,"pubInfoList":[
                    {"id":501,"title":"主播专辑庚","coverPath":"g.jpg","anchorNickName":"主播戊","playCount":8000,"trackCount":23}]}}}""")
                url.contains("radio-first-page-app/homePage") -> body("""{"ret":0,"data":{"location":"北京","modules":[
                    {"type":"FOCUS","name":"头部调频","radios":[{"id":601,"name":"测试电台","coverLarge":"h.jpg","programName":"节目","playUrl":{"aac64":"https://live.example/601/64.m3u8"}}]}]}}""")
                url.contains("recommend/album") -> body("""{"ret":0,"list":[
                    {"moduleType":"ad","list":[{"albumId":1,"title":"sponsored"}]},
                    {"moduleType":"guessYouLike","list":[{"albumId":102,"title":"猜你喜欢乙","pic":"b.jpg"}]},
                    {"moduleType":"live","list":[{"roomId":701,"id":702,"name":"测试直播间","nickname":"主播辛","coverMiddle":"i.jpg","status":9,"playCount":123}]}]}""")
                else -> body("""{"ret":0,"data":{}}""")
            }
        })
        val repo = DiscoveryRepository(api, null, { null }, System::currentTimeMillis, Dispatchers.IO, scope)

        fun nodes(text: String): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(uiAutomation.rootInActiveWindow)
            return found
        }
        fun waitFor(label: String, timeoutMs: Long = 15_000, test: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (!test() && SystemClock.uptimeMillis() < end) Thread.sleep(50)
            check(test()) { label }
        }
        fun click(text: String) {
            var done = false
            waitFor(text) {
                if (!done) for (node in nodes(text)) {
                    var parent: AccessibilityNodeInfo? = node
                    while (parent != null) {
                        if (parent.isClickable && parent.isEnabled && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done = true; break }
                        parent = parent.parent
                    }
                    if (done) break
                }
                done
            }
        }
        fun back() { runOnMainSync { activity!!.onBackPressedDispatcher.onBackPressed() }; Thread.sleep(400) }

        try {
            field.set(null, repo)
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            waitFor("home ready") { nodes("继续收听").isNotEmpty() }
            click("发现")
            waitFor("recommend loaded") { nodes("猜你喜欢").isNotEmpty() && nodes("推荐专辑甲").isNotEmpty() }
            check(nodes("sponsored").isEmpty()) // ad module never rendered

            click("分类")
            waitFor("category grid") { nodes("历史").isNotEmpty() }
            click("历史")
            waitFor("category page") { nodes("分类专辑丙").isNotEmpty() && nodes("正史").isNotEmpty() }
            click("筛选")
            waitFor("filter sheet") { nodes("是否完本").isNotEmpty() }
            click("已完本")
            click("应用")
            waitFor("filtered page") { nodes("分类分页丁").isNotEmpty() }
            back()
            waitFor("back to grid") { nodes("历史").isNotEmpty() }

            click("广播")
            waitFor("radio modules") { nodes("头部调频").isNotEmpty() && nodes("测试电台").isNotEmpty() }
            waitFor("live rooms") { nodes("测试直播间").isNotEmpty() }

            click("榜单")
            waitFor("rank list") { nodes("热播榜").isNotEmpty() && nodes("榜单专辑己").isNotEmpty() }

            click("主播")
            waitFor("anchor board") { nodes("有声书主播").isNotEmpty() && nodes("主播戊").isNotEmpty() }
            click("主播戊")
            waitFor("anchor detail") { nodes("主播专辑庚").isNotEmpty() && nodes("共 8 张，展示前 1 张").isNotEmpty() }

            result.putString("result", "PASS")
            finish(-1, result)
        } catch (failure: Throwable) {
            result.putString("failure", failure.stackTraceToString())
            finish(0, result)
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            scope.cancel()
            field.set(null, original)
        }
    }
}
