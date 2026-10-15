package cn.campus.notes.integration

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference

/** Redacted diagnostic evidence, no addresses, headers, keys, exception messages or content. */
internal class RecognitionTrace {
    private val started=System.nanoTime()
    private val phase=AtomicReference("准备请求")
    private val outcome=AtomicReference("")
    @Volatile private var ended:Long?=null
    @Volatile private var httpStatus:Int?=null
    fun stage(value:String){if(outcome.get().isEmpty())phase.set(value)}
    fun finish(value:String){if(outcome.compareAndSet("",value))ended=System.nanoTime()}
    private fun elapsed()=(ended?:System.nanoTime())-started
    fun summary()="阶段：${phase.get()}，耗时约${elapsed()/1_000_000_000}秒"
    fun json()=JSONObject().put("phase",phase.get()).put("elapsedMs",elapsed()/1_000_000)
        .put("outcome",outcome.get()).put("httpStatus",httpStatus?:JSONObject.NULL).toString()
    fun listener(delegate:EventListener)=object:EventListener(){
        override fun callStart(call:Call){stage("准备请求");delegate.callStart(call)}
        override fun dnsStart(call:Call,domainName:String){stage("解析服务地址");delegate.dnsStart(call,domainName)}
        override fun dnsEnd(call:Call,domainName:String,inetAddressList:List<InetAddress>){delegate.dnsEnd(call,domainName,inetAddressList)}
        override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy){stage("建立连接");delegate.connectStart(call,inetSocketAddress,proxy)}
        override fun secureConnectStart(call:Call){stage("验证加密连接");delegate.secureConnectStart(call)}
        override fun secureConnectEnd(call:Call,handshake:Handshake?){delegate.secureConnectEnd(call,handshake)}
        override fun connectEnd(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy,protocol:Protocol?){delegate.connectEnd(call,inetSocketAddress,proxy,protocol)}
        override fun requestBodyStart(call:Call){stage("发送本次授权资料");delegate.requestBodyStart(call)}
        override fun requestBodyEnd(call:Call,byteCount:Long){stage("等待服务响应");delegate.requestBodyEnd(call,byteCount)}
        override fun responseHeadersEnd(call:Call,response:Response){httpStatus=response.code;stage("接收服务结果");delegate.responseHeadersEnd(call,response)}
        override fun responseBodyEnd(call:Call,byteCount:Long){stage("校验识别结果");delegate.responseBodyEnd(call,byteCount)}
        override fun callFailed(call:Call,ioe:IOException){finish("transport_failure");delegate.callFailed(call,ioe)}
        override fun callEnd(call:Call){delegate.callEnd(call)}
    }
}
