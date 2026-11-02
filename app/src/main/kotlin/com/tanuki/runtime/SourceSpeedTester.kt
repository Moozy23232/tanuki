package com.tanuki.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL

// Results are samples, never a substitute for the installer's complete SHA-256 verification.
enum class SourceProbeStatus { TESTING, SUCCESS, FAILED }
data class SourceProbeResult(val sourceId:String,val status:SourceProbeStatus,val latencyMs:Long=0,
    val bytesPerSecond:Long=0,val received:Long=0,val error:String="")

data class SourceSpeedSnapshot(val running:Boolean=false,val results:Map<String,SourceProbeResult> = emptyMap(),
    val measuredAtMillis:Long=0) {
    /** Best successful source for one 256 KiB sample including request latency; ties break by sourceId. */
    val bestSourceId:String? get()=results.values.asSequence()
        .mapNotNull { result->normalizedSampleCostMillis(result)?.let { result.sourceId to it } }
        .minWithOrNull(compareBy<Pair<String,Long>>({ it.second },{ it.first }))?.first
}

private const val SAMPLE_BYTES=262_144L
private const val MIN_SAMPLE_BYTES=1_024L
private const val MAX_CONCURRENT=3
private const val MAX_REDIRECTS=5
private const val CONNECT_TIMEOUT_MILLIS=3_000
private const val READ_TIMEOUT_MILLIS=3_000
private const val BATCH_TIMEOUT_MILLIS=12_000L

// Only the public mirrors shipped by RuntimeComponents; user supplied URLs are never probed.
private val ALLOWED_HOSTS=setOf("cdimage.ubuntu.com","repo.huaweicloud.com","mirrors.huaweicloud.com",
    "mirrors.tuna.tsinghua.edu.cn","mirrors.ustc.edu.cn","nodejs.org","npmmirror.com",
    "mirrors.aliyun.com","mirror.nju.edu.cn","mirrors.bfsu.edu.cn","mirrors.cloud.tencent.com","registry.npmjs.org")

/** Approximate wall time for a full sample: latency plus the same 256 KiB at the observed rate. */
private fun normalizedSampleCostMillis(result:SourceProbeResult):Long? {
    if(result.status!=SourceProbeStatus.SUCCESS)return null
    val transferMillis=SAMPLE_BYTES*1000L/result.bytesPerSecond.coerceAtLeast(1L)
    val latencyMillis=result.latencyMs.coerceAtLeast(0L)
    return if(transferMillis>Long.MAX_VALUE-latencyMillis)Long.MAX_VALUE else transferMillis+latencyMillis
}

class SourceSpeedTester(private val scope:CoroutineScope) {
    val state=MutableStateFlow(SourceSpeedSnapshot())
    private val lock=Any()
    private var generation=0
    private var job:Job?=null
    private val active=mutableSetOf<HttpURLConnection>()

    fun test(sources:List<RuntimeSource>) {
        val targets=sources.distinctBy { it.id }
        if(targets.isEmpty())return
        synchronized(lock) {
            if(state.value.running || !scope.isActive)return
            val token=++generation
            state.value=SourceSpeedSnapshot(true,targets.associate { it.id to SourceProbeResult(it.id,SourceProbeStatus.TESTING) })
            job=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) { runBatch(targets,token) }.also { it.start() }
        }
    }
    fun cancel()=stop(false)
    fun clear()=stop(true)

    /** Manual stop: rows still TESTING end as FAILED and no completion timestamp is published. */
    private fun stop(clear:Boolean) {
        val (runningJob,connections)=synchronized(lock) {
            generation++
            val snapshot=state.value
            val previous=job
            val stale=active.toList()
            active.clear()
            job=null
            state.value=when {
                clear->SourceSpeedSnapshot()
                !snapshot.running->snapshot
                else->SourceSpeedSnapshot(results=snapshot.results.mapValues { (_,result)->
                    if(result.status==SourceProbeStatus.TESTING)result.copy(status=SourceProbeStatus.FAILED,error="已取消") else result })
            }
            previous to stale
        }
        runningJob?.cancel()
        connections.forEach { runCatching { it.disconnect() } }
    }

    private suspend fun runBatch(sources:List<RuntimeSource>,token:Int) {
        val permits=Semaphore(MAX_CONCURRENT)
        val batch=SupervisorJob(currentCoroutineContext()[Job])
        val batchScope=CoroutineScope(currentCoroutineContext()+batch+Dispatchers.IO)
        val probes=sources.map { source->batchScope.launch { probeAndPublish(source,token,permits) } }
        var timedOut=false
        var aborted=false
        try {
            timedOut=withTimeoutOrNull(BATCH_TIMEOUT_MILLIS) { probes.joinAll() }==null
        } catch(e:CancellationException) {
            aborted=true
            throw e
        } finally {
            // A blocking read can outlive the batch; the generation guard ignores its late result.
            if(timedOut||aborted)batch.cancel() else batch.complete()
            if(timedOut)disconnectActive()
            finish(token,timedOut,aborted)
        }
    }

    private suspend fun probeAndPublish(source:RuntimeSource,token:Int,permits:Semaphore) {
        val result=try {
            permits.withPermit { probe(source,token) }
        } catch(e:CancellationException) {
            throw e
        } catch(e:Exception) {
            SourceProbeResult(source.id,SourceProbeStatus.FAILED,error=friendly(e))
        }
        publish(token,result)
    }

    private fun publish(token:Int,result:SourceProbeResult) {
        synchronized(lock) {
            if(token!=generation)return
            val snapshot=state.value
            if(!snapshot.running)return
            state.value=snapshot.copy(results=snapshot.results+(result.sourceId to result))
        }
    }

    /** Completes the batch: pending rows become failures and only a finished batch gets a timestamp. */
    private fun finish(token:Int,timedOut:Boolean,aborted:Boolean) {
        synchronized(lock) {
            if(token!=generation)return
            generation++
            val pending=when {
                timedOut->"测速超时"
                aborted->"测速已取消"
                else->"测速未完成"
            }
            state.value=SourceSpeedSnapshot(false,state.value.results.mapValues { (_,result)->
                if(result.status==SourceProbeStatus.TESTING)result.copy(status=SourceProbeStatus.FAILED,error=pending) else result
            },if(aborted)0L else System.currentTimeMillis())
            job=null
        }
    }

    private fun disconnectActive() {
        val connections=synchronized(lock) { active.toList().also { active.clear() } }
        connections.forEach { runCatching { it.disconnect() } }
    }

    private suspend fun probe(source:RuntimeSource,token:Int):SourceProbeResult {
        val start=System.nanoTime()
        var address=URL(source.url)
        for(hop in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            requireAllowed(address)
            val connection=address.openConnection() as HttpURLConnection
            synchronized(lock) {
                if(token!=generation)throw CancellationException("测速已取消")
                active+=connection
            }
            try {
                connection.instanceFollowRedirects=false
                connection.useCaches=false
                connection.connectTimeout=CONNECT_TIMEOUT_MILLIS
                connection.readTimeout=READ_TIMEOUT_MILLIS
                connection.setRequestProperty("Range","bytes=0-${SAMPLE_BYTES-1}")
                connection.setRequestProperty("Accept-Encoding","identity")
                connection.setRequestProperty("Accept","application/gzip,application/octet-stream,*/*")
                connection.setRequestProperty("User-Agent","Tanuki")
                val code=connection.responseCode
                currentCoroutineContext().ensureActive()
                if(code in 300..399) {
                    check(hop<MAX_REDIRECTS) { "跳转次数过多" }
                    val target=connection.getHeaderField("Location") ?: error("下载源缺少跳转地址")
                    address=URL(address,target)
                    continue
                }
                check(code==200||code==206) { "下载源返回 HTTP $code" }
                validateResponse(connection,code,source)
                return readSample(connection,source,start)
            } finally {
                synchronized(lock) { active-=connection }
                runCatching { connection.disconnect() }
            }
        }
        error("跳转次数过多")
    }

    private fun validateResponse(connection:HttpURLConnection,code:Int,source:RuntimeSource) {
        val expected=source.expectedBytes
        if(code==206) {
            val range=connection.getHeaderField("Content-Range")
            check(range!=null) { "下载源返回了不匹配的范围" }
            check(range.startsWith("bytes 0-")) { "下载源返回了不匹配的范围" }
            if(expected>0) {
                val total=range.substringAfter('/',"").trim().toLongOrNull()
                check(total==null||total==expected) { "下载源文件大小与预期不符" }
            }
        } else if(expected>0) {
            val length=connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
            check(length==null||length==expected) { "下载源文件大小与预期不符" }
        }
    }

    private suspend fun readSample(connection:HttpURLConnection,source:RuntimeSource,start:Long):SourceProbeResult {
        var received=0L
        var first=-1
        var second=-1
        var firstAt=0L
        var finishedAt=0L
        val buffer=ByteArray(32*1024)
        connection.inputStream.use { stream->
            while(received<SAMPLE_BYTES) {
                currentCoroutineContext().ensureActive()
                val want=minOf(buffer.size.toLong(),SAMPLE_BYTES-received).toInt()
                val count=stream.read(buffer,0,want)
                if(count<0)break
                if(count==0)continue
                if(first<0) {
                    first=buffer[0].toInt() and 255
                    if(count>1)second=buffer[1].toInt() and 255
                    firstAt=System.nanoTime()
                } else if(second<0) {
                    second=buffer[0].toInt() and 255
                }
                received+=count
            }
            finishedAt=System.nanoTime()
        }
        check(received>=MIN_SAMPLE_BYTES) { "下载源返回的数据过少" }
        check(first==0x1f && second==0x8b) { "下载源返回的不是有效压缩包，可能被拦截" }
        val duration=(finishedAt-firstAt).coerceAtLeast(1_000_000L)
        val sampled=(received-1).coerceIn(0L,SAMPLE_BYTES-1)
        val bytesPerSecond=sampled*1_000_000_000L/duration
        val latency=((firstAt-start)/1_000_000L).coerceAtLeast(0L)
        return SourceProbeResult(source.id,SourceProbeStatus.SUCCESS,latency,bytesPerSecond,received)
    }

    private fun requireAllowed(address:URL) {
        require(address.protocol.equals("https",ignoreCase=true)) { "测速地址必须使用 HTTPS" }
        require(address.userInfo==null) { "测速地址不能包含凭据" }
        require(address.port==-1||address.port==443) { "测速地址端口不受支持" }
        val host=address.host.lowercase()
        require(ALLOWED_HOSTS.any { host==it || host.endsWith(".$it") }) { "不是已知的公共下载源" }
    }

    private fun friendly(t:Exception):String {
        val raw=when(t) {
            is java.net.SocketTimeoutException->"连接或读取超时"
            is java.net.UnknownHostException->"无法解析下载源"
            is java.net.ConnectException->"无法连接下载源"
            is javax.net.ssl.SSLException->"TLS 连接失败"
            is java.io.IOException->t.message ?: "网络读取失败"
            else->t.message ?: "测速失败"
        }
        return Redact.text(raw).replace(Regex("https?://\\S+"),"[地址]").take(160).ifBlank { "测速失败" }
    }
}
