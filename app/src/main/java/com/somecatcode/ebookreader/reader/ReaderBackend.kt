package com.somecatcode.ebookreader.reader

import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.EbookApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** A server resource of the ONE book a [ReaderRequestProxyImpl] is bound to. */
sealed interface ReaderResource {
    /** The whole file (WebDAV), `Range` aware. */
    data object Book : ReaderResource

    /** `archive/{id}/entries` JSON. */
    data object ArchiveEntries : ReaderResource

    /** One archive entry (`item/{id}?id=`). */
    data class Item(val entry: String) : ReaderResource

    /** `comic/{id}/pages` JSON. */
    data object ComicPages : ReaderResource

    /** One scaled comic page. */
    data class ComicPage(val index: Int, val width: Int) : ReaderResource
}

/**
 * Answer of a [ReaderBackend]. [headers] use lower-case names. Closing releases the connection;
 * [body] closes it too once it is closed.
 */
class BackendResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: InputStream? = null,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body?.close() }
        onClose()
    }
}

/**
 * The only thing the reader proxy needs from the data layer: authenticated access to the resources
 * of one book. W-DATA's API client satisfies it through [EbookApiReaderBackend].
 */
interface ReaderBackend {
    /**
     * Blocking (called on the WebView IO thread). HTTP error statuses are returned as responses
     * (401 -> [BackendResponse.code] 401, ...), only transport problems throw.
     *
     * @param range value of the `Range` request header or null
     * @throws IOException no connection, DNS, TLS, timeout
     */
    @Throws(IOException::class)
    fun fetch(resource: ReaderResource, range: String?): BackendResponse
}

/**
 * [ReaderBackend] on top of [EbookApi]: WebDAV file via [EbookApi.davFileUrl], item and comic page
 * through the URL builders, the JSON lists through the typed calls (re-encoded).
 *
 * @param userId Nextcloud user id of the account (for the WebDAV path)
 * @param davPath path of the book inside the user's files (`BookDto.path`)
 */
class EbookApiReaderBackend(
    private val api: EbookApi,
    private val fileId: Long,
    private val userId: String,
    private val davPath: String,
) : ReaderBackend {

    override fun fetch(resource: ReaderResource, range: String?): BackendResponse = when (resource) {
        ReaderResource.Book -> http(api.davFileUrl(userId, davPath).toString(), range)
        is ReaderResource.Item -> http(api.itemUrl(fileId, resource.entry).toString(), null)
        is ReaderResource.ComicPage -> http(api.comicPageUrl(fileId, resource.index, resource.width).toString(), null)
        ReaderResource.ArchiveEntries -> json { ApiJson.encodeToString(api.archiveEntries(fileId)) }
        ReaderResource.ComicPages -> json { ApiJson.encodeToString(api.comicPages(fileId)) }
    }

    private fun http(url: String, range: String?): BackendResponse {
        val request = Request.Builder().url(url).get().apply { range?.let { header("Range", it) } }.build()
        val response = api.http.execute(request)
        val headers = response.headers.toMultimap().mapValues { it.value.last() }.mapKeys { it.key.lowercase() }
        return BackendResponse(response.code, headers, response.body.byteStream(), onClose = { response.close() })
    }

    private fun json(block: suspend () -> String): BackendResponse = try {
        val text = runBlocking { block() }
        BackendResponse(200, mapOf("content-type" to "application/json; charset=utf-8"), text.byteInputStream())
    } catch (e: ApiException) {
        when (e) {
            is ApiException.Network -> throw IOException(e.message, e)
            is ApiException.Unauthorized -> BackendResponse(401)
            is ApiException.Forbidden -> BackendResponse(403)
            is ApiException.NotFound -> BackendResponse(404)
            is ApiException.BadRequest -> BackendResponse(400)
            is ApiException.RateLimited -> BackendResponse(429)
            is ApiException.Server -> BackendResponse(e.statusCode.takeIf { it in 500..599 } ?: 502)
        }
    }
}
