package com.tanuki.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** File-only handoff for explicit official-page blob downloads. No command or file-read API. */
class BlobDownloads(private val context: Context, private val view: WebView,
                    private val ready: (File, String) -> Unit,
                    private val progress: com.tanuki.runtime.FileTransfers? = null,
                    private val error: (String) -> Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var transfer:Job?=null
    private val handler=Handler(Looper.getMainLooper())
    private var origin:String?=null
    private var registered=false
    private data class Pending(val nonce:String,val name:String)
    private var pending:Pending?=null
    private var progressId:Long?=null
    private val timeout=Runnable { if(pending!=null){clear();error("网页 Blob 下载超时，请重试")} }
    fun bind(nextOrigin:String) {
        val enabled=context.getSharedPreferences("web",Context.MODE_PRIVATE).getBoolean("blobExport",true)
        if(origin==nextOrigin && registered && enabled)return
        detach()
        origin=nextOrigin
        if(!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))return
        WebViewCompat.addWebMessageListener(view,NAME,setOf(nextOrigin)) { _,message,source,isMainFrame,_ ->
            val request=pending ?: return@addWebMessageListener
            if(!isMainFrame || !OriginPolicy.sameOrigin(source.toString(),origin))return@addWebMessageListener
            if(message.type!=WebMessageCompat.TYPE_STRING)return@addWebMessageListener
            val text=message.data ?: return@addWebMessageListener
            if(text.length>MAX_BYTES*4/3+4096){clear();error("Blob 下载超过 8MiB 上限，请使用项目导出");return@addWebMessageListener}
            try {
                val json=JSONObject(text)
                if(json.optString("nonce")!=request.nonce)return@addWebMessageListener
                if(json.has("error")){clear();error("网页无法读取 Blob，或文件超过 8MiB 上限");return@addWebMessageListener}
                val encoded=json.getString("data")
                val id=progressId
                pending=null;handler.removeCallbacks(timeout)
                transfer=scope.launch {
                    var file:File?=null
                    try {
                        withContext(Dispatchers.IO) {
                            val bytes=Base64.decode(encoded,Base64.DEFAULT)
                            require(bytes.size<=MAX_BYTES)
                            file=File.createTempFile("tanuki-blob-",".download",context.cacheDir)
                            file!!.writeBytes(bytes)
                            id?.let { progress?.update(it,bytes.size.toLong(),bytes.size.toLong()) }
                        }
                        if(registered && origin==nextOrigin)ready(file!!,request.name) else file?.delete()
                    }catch(_:CancellationException){file?.delete()}
                    catch(_:Exception){file?.delete();error("Blob 下载内容无效，未导出文件")}
                    finally { id?.let { progress?.finish(it) };if(progressId==id)progressId=null }
                }
            }catch(_:Exception){clear();error("Blob 下载内容无效，未导出文件")}
        }
        registered=true
    }
    fun request(url:String,name:String) {
        if(!registered || !url.startsWith("blob:") || !OriginPolicy.sameOrigin(url.removePrefix("blob:"),origin)){error("此 WebView 或下载来源不支持 Blob 导出");return}
        if(pending!=null || transfer?.isActive==true){error("请先完成当前文件下载");return}
        val nonce=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString(""){"%02x".format(it)}
        pending=Pending(nonce,File(name).name)
        progressId=progress?.begin("准备网页 Blob 下载")
        handler.postDelayed(timeout,30000)
        val literal=JSONObject.quote(url)
        val ticket=JSONObject.quote(nonce)
        view.evaluateJavascript("""
            (async () => {
              const nonce=$ticket;
              try {
                const blob=await (await fetch($literal)).blob();
                if(blob.size>$MAX_BYTES) throw new Error('超过 8MiB 上限');
                const reader=new FileReader();
                reader.onerror=()=>window.$NAME.postMessage(JSON.stringify({nonce,error:'读取失败'}));
                reader.onload=()=>window.$NAME.postMessage(JSON.stringify({nonce,data:String(reader.result).split(',',2)[1]}));
                reader.readAsDataURL(blob);
              } catch (_) { window.$NAME.postMessage(JSON.stringify({nonce,error:'读取失败或超过 8MiB 上限'})); }
            })();
        """.trimIndent(),null)
    }
    private fun clear(){pending=null;handler.removeCallbacks(timeout);progressId?.let { progress?.finish(it) };progressId=null}
    private fun detach(){clear();transfer?.cancel();transfer=null;if(registered)runCatching{WebViewCompat.removeWebMessageListener(view,NAME)};registered=false;origin=null}
    fun dispose(){detach();scope.cancel()}
    companion object { const val NAME="__tanukiFileExport";const val MAX_BYTES=8*1024*1024 }
}
