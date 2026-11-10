package com.xcloud.tclnative.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
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

class CloudHttpException(val stage: String, val status: Int, val serviceCode: String?, requestId: String?) :
    IOException("$stage: HTTP $status" + (serviceCode?.let { " — código $it" } ?: "") +
        (requestId?.let { " (requisição $it)" } ?: ""))

/** Preserve the failed request stage; raw socket messages can contain private endpoint URLs. */
class CloudRequestException(val stage: String,cause: IOException):
    IOException("$stage: falha na requisição (${cause.javaClass.simpleName})",cause)

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
        throw CloudHttpException(stage, code, error, id)
    }
    return text
}
