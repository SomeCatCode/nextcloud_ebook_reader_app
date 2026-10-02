package com.somecatcode.ebookreader.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** `User-Agent` of all requests; Nextcloud shows it as the name of the created app password. */
const val USER_AGENT = "E-Book Reader (Android)"

/** Suspends until the call finishes; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/**
 * Maps a non-successful HTTP answer to an [ApiException] (CONTRACTS section 2). [body] is the
 * (possibly empty) response text, used only for the error message.
 */
fun mapHttpError(code: Int, retryAfter: String?, body: String): ApiException {
    val message = ocsMessage(body)
    return when (code) {
        401 -> ApiException.Unauthorized(message ?: "Unauthorized")
        403 -> ApiException.Forbidden(message ?: "Forbidden")
        404 -> ApiException.NotFound(message ?: "Not found")
        429 -> ApiException.RateLimited(retryAfter?.trim()?.toLongOrNull())
        in 500..599 -> ApiException.Server(code, message ?: "Server error $code")
        else -> ApiException.BadRequest(message ?: "HTTP $code")
    }
}

/** Extracts `ocs.meta.message` (or a top-level `message`) from a JSON error body, if there is one. */
fun ocsMessage(body: String): String? {
    if (body.isBlank() || !body.trimStart().startsWith("{")) return null
    return runCatching {
        val root = ApiJson.parseToJsonElement(body).jsonObject
        val meta = (root["ocs"] as? JsonObject)?.get("meta") as? JsonObject
        ((meta?.get("message") ?: root["message"]) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * [AuthenticatedHttp] for one account: adds `Authorization: Basic` and `OCS-APIRequest: true` and
 * only to requests for the account's own host. Redirects are followed manually for the same host
 * only (re-adding the header); a redirect to another host is a failure so the password never leaks.
 */
class AuthenticatedHttpImpl(
    baseClient: OkHttpClient,
    serverUrl: HttpUrl,
    private val loginName: String,
    private val appPassword: String,
) : AuthenticatedHttp {

    private val serverHost = serverUrl.host
    private val serverPort = serverUrl.port

    val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun authorize(request: Request): Request {
        val url = request.url
        if (url.host != serverHost || url.port != serverPort) return request
        return request.newBuilder()
            .header("Authorization", Credentials.basic(loginName, appPassword, Charsets.UTF_8))
            .header("OCS-APIRequest", "true")
            .header("User-Agent", USER_AGENT)
            .build()
    }

    override fun execute(request: Request): Response {
        var current = authorize(request)
        var hops = 0
        while (true) {
            val response = client.newCall(current).execute()
            val next = redirectTarget(response, current, hops) ?: return response
            response.close()
            current = authorize(next)
            hops++
        }
    }

    /** Suspending variant used by [EbookApiImpl]; cancellable. */
    suspend fun await(request: Request): Response {
        var current = authorize(request)
        var hops = 0
        while (true) {
            val response = client.newCall(current).await()
            val next = redirectTarget(response, current, hops) ?: return response
            response.close()
            current = authorize(next)
            hops++
        }
    }

    private fun redirectTarget(response: Response, request: Request, hops: Int): Request? {
        if (!response.isRedirect) return null
        if (hops >= MAX_REDIRECTS) throw IOException("Too many redirects")
        val location = response.header("Location") ?: return null
        val target = request.url.resolve(location) ?: throw IOException("Invalid redirect")
        if (target.host != serverHost || target.port != serverPort || target.scheme != "https" && request.url.scheme == "https") {
            throw IOException("Refusing redirect to another host")
        }
        val keepsBody = response.code == 307 || response.code == 308
        val builder = request.newBuilder().url(target)
        if (!keepsBody) builder.method(if (request.method == "HEAD") "HEAD" else "GET", null)
        return builder.build()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}

internal fun rethrowCancellation(e: Throwable) {
    if (e is CancellationException) throw e
}
