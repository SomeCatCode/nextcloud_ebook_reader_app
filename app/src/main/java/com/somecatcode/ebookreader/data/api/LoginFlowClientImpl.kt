package com.somecatcode.ebookreader.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Login Flow v2 (`POST {server}/index.php/login/v2`, then poll). No credentials involved. */
class LoginFlowClientImpl(
    private val client: OkHttpClient,
    private val json: Json = ApiJson,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Only for unit tests against a plain-HTTP MockWebServer; never set in the app. */
    private val allowInsecure: Boolean = false,
) : LoginFlowClient {

    override suspend fun start(serverInput: String): LoginFlowStart {
        val server = normalizeServerInput(serverInput, allowInsecure)
        val request = Request.Builder()
            .url("$server/index.php/login/v2")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(FormBody.Builder().build())
            .build()
        val start = call(request) { code, text -> decode(code, text, LoginFlowStart.serializer()) }
        if (!allowInsecure) {
            requireHttps(start.poll.endpoint)
            requireHttps(start.login)
        }
        return start
    }

    override suspend fun pollOnce(poll: LoginFlowPoll): LoginFlowResult? {
        if (!allowInsecure) requireHttps(poll.endpoint)
        val request = Request.Builder()
            .url(poll.endpoint)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("token", poll.token).build())
            .build()
        return call(request, allowNotFound = true) { code, text ->
            if (code == 404) null else decode(code, text, LoginFlowResult.serializer())
        }
    }

    private suspend fun <T> call(request: Request, allowNotFound: Boolean = false, parse: (Int, String) -> T): T =
        withContext(io) {
            val response: Response = try {
                client.newBuilder().followRedirects(false).build().newCall(request).execute()
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
            response.use { r ->
                val text = try {
                    r.body.string()
                } catch (e: IOException) {
                    throw ApiException.Network(e)
                }
                if (r.code == 404 && allowNotFound) return@use parse(404, text)
                if (!r.isSuccessful) throw mapHttpError(r.code, r.header("Retry-After"), text)
                parse(r.code, text)
            }
        }

    private fun <T> decode(code: Int, text: String, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw ApiException.Server(code, "Unexpected response: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ApiException.Server(code, "Unexpected response: ${e.message}")
    }

    private fun requireHttps(url: String) {
        if (url.toHttpUrlOrNull()?.isHttps != true) throw ApiException.BadRequest("Only HTTPS is supported")
    }

    companion object {
        /**
         * Normalises user input: trims, adds `https://`, drops query/fragment, trailing slashes and a
         * trailing `/index.php` or `/login`. Plain `http://` is rejected (v1: HTTPS only).
         */
        fun normalizeServerInput(input: String, allowInsecure: Boolean = false): String {
            var s = input.trim()
            if (s.isEmpty()) throw ApiException.BadRequest("Empty server address")
            if (!s.contains("://")) s = "https://$s"
            if (!allowInsecure && s.startsWith("http://", ignoreCase = true)) throw ApiException.BadRequest("Only HTTPS is supported")
            val url = s.toHttpUrlOrNull() ?: throw ApiException.BadRequest("Invalid server address")
            if (!allowInsecure && !url.isHttps) throw ApiException.BadRequest("Only HTTPS is supported")
            var path = url.encodedPath.trimEnd('/')
            for (suffix in listOf("/index.php/login/v2", "/index.php/login", "/login/v2", "/login", "/index.php")) {
                if (path.endsWith(suffix)) {
                    path = path.removeSuffix(suffix).trimEnd('/')
                    break
                }
            }
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
                .toString().trimEnd('/') + path
        }
    }
}

/** Default poll interval and timeout of Login Flow v2 (CONTRACTS section 2). */
const val LOGIN_POLL_INTERVAL_MS = 2_000L
const val LOGIN_POLL_TIMEOUT_MS = 20L * 60 * 1000

/**
 * Polls until the user finished the login in the browser. Returns null after [timeoutMs] without
 * success; cancelling the calling coroutine stops the polling. Network hiccups while polling are
 * ignored (the next attempt retries); other errors are rethrown.
 */
suspend fun LoginFlowClient.awaitLogin(
    poll: LoginFlowPoll,
    intervalMs: Long = LOGIN_POLL_INTERVAL_MS,
    timeoutMs: Long = LOGIN_POLL_TIMEOUT_MS,
    sleep: suspend (Long) -> Unit = { delay(it) },
): LoginFlowResult? {
    var waited = 0L
    while (waited <= timeoutMs) {
        try {
            pollOnce(poll)?.let { return it }
        } catch (e: ApiException.Network) {
            // transient, try again
        } catch (e: ApiException.Server) {
            // transient, try again
        }
        sleep(intervalMs)
        waited += intervalMs
    }
    return null
}
