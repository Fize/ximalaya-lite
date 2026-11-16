package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*

/** Controlled responses matching the original client's object/string data and nullable primitive decoding. */
class SyncSchemaVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle();val checks=mutableListOf<String>();val failures=mutableListOf<String>()
        val lease=AccountLease(1,AccountSession(88001,"synthetic-schema"),Any())
        val database=ListeningDatabase.open(targetContext,"sync-schema-only.db")
        fun run(name:String,block:()->Unit) { try { block();checks.add(name) } catch(e:Throwable) { failures.add("$name: ${e.javaClass.simpleName}") } }
        fun api(data:Any, subscriptions:Any=JSONObject().put("list",JSONArray().put(JSONObject().put("albumId",901).put("title","订阅夹具"))), ret:Int=0):CloudApi {
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                check(chain.request().method=="GET")
                val payload=if(chain.request().url.encodedPath.contains("history/query")) data else JSONObject().put("subscribeAlbumResult",subscriptions)
                val json=JSONObject().put("ret",ret).put("data",payload)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(json.toString().toResponseBody()).build()
            }.build()
            return CloudApi(targetContext,client,current={it===lease})
        }
        fun history(progress:Any?=12):JSONObject {
            val row=JSONObject().put("type",1).put("childId",902).put("itemId",901).put("endedAt",5000).put("length",60).put("childTitle","历史夹具")
            if(progress!=null)row.put("breakSecond",progress)
            return JSONObject().put("listenModels",JSONArray().put(row))
        }
        try {
            run("object response") { check(api(history()).read(lease).history.single().positionMs==12000L) }
            run("string encoded history data reaches subscriptions") {
                val snapshot=api(history().toString()).read(lease)
                check(snapshot.history.single().positionMs==12000L);check(snapshot.subscriptions.single().id==901L)
            }
            run("missing progress defaults to zero") { check(api(history(null)).read(lease).history.single().positionMs==0L) }
            run("null progress defaults to zero") { check(api(history(JSONObject.NULL)).read(lease).history.single().positionMs==0L) }
            run("string nested subscription result") { check(api(history(),JSONObject().put("list",JSONArray()).toString()).read(lease).subscriptions.isEmpty()) }
            run("malformed data never returns empty success") {
                for(data in listOf<Any>("broken",JSONObject(),JSONObject.NULL,JSONArray())) {
                    try { api(data).read(lease);error("must reject") } catch(expected:CloudReadFailure) { }
                }
            }
            run("invalid progress and business rejection stay failures") {
                try { api(history("bad-progress")).read(lease);error("must reject") } catch(expected:CloudReadFailure) { }
                try { api(history(),ret=924).read(lease);error("must reject") } catch(expected:CloudFailure) { check(!expected.retryable) }
            }
            run("real Room reconcile preserves newer pending history and owner isolation") {
                runBlocking {
                    val dao=database.cloud()
                    dao.record(CloudHistoryRow(lease.owner,902,901,"newer local","",30000,60000,false,9000),77)
                    dao.subscribe(CloudSubscriptionRow(lease.owner,999,"local subscription","",true),78)
                    dao.putHistory(CloudHistoryRow("account:88002",902,901,"other owner","",45000,60000,false,10000))
                    dao.reconcile(lease.owner,api(history(null).toString()).read(lease))
                    check(dao.history(lease.owner).single().positionMs==30000L)
                    check(dao.pending(lease.owner).map { it.revision }.toSet()==setOf(77L,78L))
                    check(dao.subscriptions(lease.owner).map{it.id}.toSet()==setOf(901L,999L))
                    check(dao.history("account:88002").single().positionMs==45000L)
                }
            }
            run("CloudSync reads both lists uploads progress and confirms string response") {
                val flowDb=ListeningDatabase.open(targetContext,"sync-schema-flow.db")
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
                var sync:CloudSync?=null
                try { runBlocking {
                    val session=AccountSession(88003,"synthetic-flow")
                    val store=object:SessionStore { override fun read()=session;override fun write(session:AccountSession){};override fun clear(){} }
                    val login=object:AccountGateway {
                        override fun send(mobile:String){error("no SMS")}
                        override fun login(mobile:String,code:String)=session;override fun check(session:AccountSession)=true
                        override fun refresh(session:AccountSession)=session
                    }
                    val account=AccountManager(login,store,targetContext.getSharedPreferences("schema-flow-only",0),0,scope)
                    withTimeout(5000){while(account.lease()==null)delay(20)}
                    val owner=account.lease()!!.owner
                    flowDb.cloud().record(CloudHistoryRow(owner,902,901,"pending","",30000,60000,false,9000),99)
                    var serverAt=5000L;var serverPosition=0L;var writes=0;var subscriptionReads=0
                    val client=OkHttpClient.Builder().addInterceptor { chain ->
                        val req=chain.request();val body=when {
                            req.url.encodedPath.contains("history/query") -> {
                                val payload=history(serverPosition);payload.getJSONArray("listenModels").getJSONObject(0).put("endedAt",serverAt)
                                JSONObject().put("ret",0).put("data",payload.toString())
                            }
                            req.url.encodedPath.contains("subscribe/list") -> {subscriptionReads++;JSONObject("""{"ret":0,"data":{"subscribeAlbumResult":{"list":[{"albumId":901,"title":"订阅夹具"}]}}}""")}
                            req.url.encodedPath.contains("history/merge") -> {
                                val form=req.body as FormBody
                                val record=JSONArray(form.value((0 until form.size).first { form.name(it)=="mergeRecords" })).getJSONObject(0)
                                serverAt=record.getLong("endedAt");serverPosition=record.getLong("breakSecond");writes++
                                JSONObject("""{"ret":0}""")
                            }
                            else -> error("unexpected path")
                        }
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toString().toResponseBody()).build()
                    }.build()
                    sync=CloudSync(account,flowDb.cloud(),CloudApi(targetContext,client,current=account::current,sign={"synthetic-signature"}),scope)
                    withTimeout(10000){while(sync!!.state.value.message!="服务器核对完成")delay(20)}
                    check(writes==1 && subscriptionReads==2)
                    check(flowDb.cloud().pending(owner).isEmpty())
                    check(sync!!.state.value.history.single().positionMs==30000L)
                    check(sync!!.state.value.subscriptions.single().id==901L)
                    check(syncViewText(sync!!.state.value)=="已同步")
                } } finally {sync?.close();scope.cancel();flowDb.close();targetContext.deleteDatabase("sync-schema-flow.db")}
            }
            result.putString("checks",checks.toString());result.putString("failures",failures.toString());result.putString("result",if(failures.isEmpty())"PASS" else "FAIL");finish(if(failures.isEmpty()) -1 else 0,result)
        } finally { database.close();targetContext.deleteDatabase("sync-schema-only.db") }
    }
}
