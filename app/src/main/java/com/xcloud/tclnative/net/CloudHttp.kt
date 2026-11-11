package com.xcloud.tclnative.net

import com.xcloud.tclnative.i18n.UiText

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resumeWithException

/** Cancel the socket along with the coroutine, and close responses arriving after cancellation. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}

class CloudHttpException(val stage: String, val status: Int, val serviceCode: String?, requestId: String?,
                         val retryAfterMs: Long?=null) :
    IOException(UiText.join(UiText.text("{0}: HTTP {1}",UiText.nested(stage),UiText.nested(status)),(serviceCode?.let { UiText.text(" — código {0}",UiText.nested(it)) } ?: ""),(requestId?.let { UiText.text(" (requisição {0})",UiText.nested(it)) } ?: "")))

/** Preserve the failed request stage; raw socket messages can contain private endpoint URLs. */
class CloudRequestException(val stage: String,cause: IOException):
    IOException(UiText.text("{0}: falha na requisição ({1})",UiText.nested(stage),UiText.nested(cause.javaClass.simpleName)),cause)

internal fun retryAfterDelayMs(value: String?,nowMs: Long=System.currentTimeMillis()): Long? {
    val text=value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { seconds ->
        return if(seconds<0) null else seconds.coerceAtMost(Long.MAX_VALUE/1000)*1000
    }
    return runCatching {
        val format=SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US).apply {
            timeZone=TimeZone.getTimeZone("GMT");isLenient=false
        }
        format.parse(text)?.time?.let { (it-nowMs).coerceAtLeast(0) }
    }.getOrNull()
}

/** Report diagnostic codes, never a response body, credential or signed URL. */
fun Response.checkedBody(stage: String): String {
    val text = body?.string().orEmpty()
    if (!isSuccessful) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val error = listOf("error", "XErr", "code").firstNotNullOfOrNull { key ->
            json?.opt(key)?.toString()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
        }
        val id = (header("x-ms-request-id") ?: header("ms-cv"))
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,100}")) }
        throw CloudHttpException(stage, code, error, id,retryAfterDelayMs(header("Retry-After")))
    }
    return text
}
