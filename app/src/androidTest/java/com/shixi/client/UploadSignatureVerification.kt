package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

/** Local synthetic identity; no real history is posted and no credentials/signatures are logged. */
class UploadSignatureVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out=Bundle();val checks=arrayListOf<String>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        fun waitFor(condition:()->Boolean) { val until=System.nanoTime()+15_000_000_000;while(!condition()&&System.nanoTime()<until)Thread.sleep(10);check(condition()) }
        try {
            val gateway=object:AccountGateway {
                override fun send(mobile:String){error("no SMS")}
                override fun login(mobile:String,code:String)=AccountSession(8808,"synthetic-upload-fixture")
                override fun check(session:AccountSession)=true;override fun refresh(session:AccountSession)=session
            }
            val account=AccountManager(gateway,object:SessionStore { override fun read():AccountSession?=null;override fun write(session:AccountSession){};override fun clear(){} },targetContext.getSharedPreferences("upload-contract-only",0),0,scope)
            waitFor{!account.state.value.busy};account.login("13800000000","123456");waitFor{account.state.value.signedIn&&!account.state.value.busy}
            val lease=account.lease()!!;var request:Request?=null;var signed:Map<String,String>?=null
            val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
                request=chain.request();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body("""{"ret":0,"data":{"syncPoint":1}}""".toResponseBody()).build()
            }.build()
            CloudApi(targetContext,client,current=account::current,sign={fields->signed=fields;HistorySigner.sign(targetContext,fields)}).progress(lease,CloudHistoryRow(lease.owner,993425122,125160552,"synthetic record","",12000,402000,false,1794400000000))
            val sent=checkNotNull(request);val form=sent.body as FormBody
            val body=(0 until form.size).associate { form.name(it) to form.value(it) }
            val cookie=sent.header("Cookie")!!.split(';').filter{it.contains('=')}.associate{it.substringBefore('=') to it.substringAfter('=')}
            val device=cookie.getValue("1&_device").split('&');val token=cookie.getValue("1&_token").split('&')
            val wire=(body-"signature")+mapOf("uid" to token[0],"token" to token[1],"device" to device[0],"deviceId" to device[1],"version" to device[2],"channel" to cookie.getValue("channel"),"impl" to cookie.getValue("impl"))
            check(wire==signed){"Signing identity differs from request identity"}
            check(HistorySigner.sign(targetContext,wire)==body["signature"]){"Native signature could not be reconstructed from request"}
            checks.add("native signature reconstructed from uploaded fields and identity cookie")
            check(HistorySigner.sign(targetContext,wire-"channel")!=body["signature"]){"Missing channel did not affect native signature"}
            checks.add("old missing-channel identity produces a different native signature")
            check(sent.url.host=="xdcs-collector.ximalaya.com" && sent.url.isHttps && sent.method=="POST")
            check(form.contentType().toString().contains("application/x-www-form-urlencoded"))
            check("token" !in body && "uid" !in body && body.keys==setOf("mergeRecords","signature"))
            checks.add("fixed collector host, unchanged form payload and credential placement")
            account.logout();request=null
            check(runCatching { CloudApi(targetContext,client,current=account::current,sign={"fixture"}).progress(lease,CloudHistoryRow(lease.owner,1,2,"fixture","",0,0,false,1)) }.exceptionOrNull() is CloudFailure)
            check(request==null);checks.add("expired lease sends no upload")
            out.putString("result","PASS")
        } catch(t:Throwable) {out.putString("result","FAIL");out.putString("failure",t.stackTraceToString())}
        finally {scope.cancel()}
        out.putStringArrayList("checks",checks);finish(if(out.getString("result")=="PASS") -1 else 0,out)
    }
}
