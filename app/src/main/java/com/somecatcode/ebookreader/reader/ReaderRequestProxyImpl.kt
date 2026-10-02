package com.somecatcode.ebookreader.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale

/**
 * [ReaderRequestProxy] bound to one book. Offline (downloaded copy) only `/api/book` exists: the
 * page then always opens the whole file ([BookSource.File]). Online the `/api/` paths are forwarded
 * to the server through a [ReaderBackend] that adds the authentication.
 *
 * @param backendFor creates the backend for the bound book (online mode); null = no account/server
 * @param localFile the finished offline copy of the bound book or null (called on the WebView IO thread)
 */
class ReaderRequestProxyImpl(
    private val backendFor: (accountId: String, fileId: Long) -> ReaderBackend?,
    private val localFile: (accountId: String, fileId: Long) -> File?,
) : ReaderRequestProxy {

    private data class Binding(val accountId: String, val fileId: Long, val online: Boolean)

    @Volatile
    private var binding: Binding? = null

    override fun bind(accountId: String, fileId: Long, online: Boolean) {
        binding = Binding(accountId, fileId, online)
    }

    override fun intercept(request: WebResourceRequest): WebResourceResponse? =
        handle(request.method, request.url, request.requestHeaders?.get("Range") ?: request.requestHeaders?.get("range"))

    /** Testable core of [intercept]. */
    fun handle(method: String?, url: Uri, rangeHeader: String?): WebResourceResponse? {
        if (url.scheme != "https" || url.host != READER_HOST) {
            return error(403)
        }
        val path = url.encodedPath.orEmpty()
        if (path == "/reader" || path.startsWith("/reader/")) {
            return null // WebViewAssetLoader
        }
        if (method != null && method != "GET" && method != "HEAD") {
            return error(405)
        }
        val resource = parseResource(path, url) ?: return error(404)
        val bound = binding ?: return error(404)
        return if (bound.online) online(bound, resource, rangeHeader) else offline(bound, resource, rangeHeader)
    }

    private fun parseResource(path: String, url: Uri): ReaderResource? = when {
        path == "/api/book" -> ReaderResource.Book
        path == "/api/archive/entries" -> ReaderResource.ArchiveEntries
        path == "/api/item" -> url.getQueryParameter("id")?.takeIf { it.isNotEmpty() }?.let { ReaderResource.Item(it) }
        path == "/api/comic/pages" -> ReaderResource.ComicPages
        path.startsWith("/api/comic/page/") -> {
            val index = path.removePrefix("/api/comic/page/").toIntOrNull()
            val width = url.getQueryParameter("w")?.toIntOrNull() ?: 0
            if (index != null && index >= 0) ReaderResource.ComicPage(index, width.coerceIn(0, MAX_PAGE_WIDTH)) else null
        }
        else -> null
    }

    // ---- online -------------------------------------------------------------------------------

    private fun online(bound: Binding, resource: ReaderResource, range: String?): WebResourceResponse {
        val backend = backendFor(bound.accountId, bound.fileId) ?: return error(401, "unauthorized")
        val response = try {
            backend.fetch(resource, range.takeIf { resource == ReaderResource.Book })
        } catch (_: IOException) {
            return error(504, "network")
        } catch (_: RuntimeException) {
            return error(504, "network")
        }
        return when {
            response.code == 401 -> {
                response.close()
                error(401, "unauthorized")
            }
            response.code in 200..299 || response.code == 416 -> forward(response)
            response.code in 100..599 && response.code !in 300..399 -> {
                response.close()
                error(response.code)
            }
            else -> {
                response.close()
                error(502)
            }
        }
    }

    private fun forward(response: BackendResponse): WebResourceResponse {
        val headers = HashMap<String, String>()
        for (name in PASS_HEADERS) {
            response.headers[name]?.let { headers[canonical(name)] = it }
        }
        headers.putIfAbsent("Cache-Control", "no-store")
        val (mime, charset) = splitContentType(response.headers["content-type"])
        val body = response.body ?: ByteArrayInputStream(ByteArray(0))
        return WebResourceResponse(mime, charset, response.code, reason(response.code), headers, body)
    }

    // ---- offline ------------------------------------------------------------------------------

    private fun offline(bound: Binding, resource: ReaderResource, rangeHeader: String?): WebResourceResponse {
        if (resource != ReaderResource.Book) {
            return error(404)
        }
        val file = localFile(bound.accountId, bound.fileId)?.takeIf { it.isFile } ?: return error(404)
        val length = file.length()
        val mime = mimeFor(file.name)
        val base = hashMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        return when (val range = parseRange(rangeHeader, length)) {
            RangeResult.None -> {
                base["Content-Length"] = length.toString()
                WebResourceResponse(mime, null, 200, "OK", base, FileSliceStream(file, 0, length))
            }
            RangeResult.Unsatisfiable -> {
                base["Content-Range"] = "bytes */$length"
                WebResourceResponse(mime, null, 416, "Range Not Satisfiable", base, ByteArrayInputStream(ByteArray(0)))
            }
            is RangeResult.Satisfiable -> {
                val size = range.end - range.start + 1
                base["Content-Length"] = size.toString()
                base["Content-Range"] = "bytes ${range.start}-${range.end}/$length"
                WebResourceResponse(mime, null, 206, "Partial Content", base, FileSliceStream(file, range.start, size))
            }
        }
    }

    private fun error(status: Int, readerError: String? = null): WebResourceResponse {
        val headers = hashMapOf("Cache-Control" to "no-store")
        readerError?.let { headers["X-Reader-Error"] = it }
        return WebResourceResponse("text/plain", "utf-8", status, reason(status), headers, ByteArrayInputStream(ByteArray(0)))
    }

    internal sealed interface RangeResult {
        data object None : RangeResult
        data object Unsatisfiable : RangeResult
        data class Satisfiable(val start: Long, val end: Long) : RangeResult
    }

    companion object {
        const val READER_HOST = "appassets.androidplatform.net"
        private const val MAX_PAGE_WIDTH = 8192
        private val PASS_HEADERS = listOf("content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified")

        private fun canonical(name: String): String =
            name.split('-').joinToString("-") { part -> part.replaceFirstChar { it.titlecase(Locale.ROOT) } }

        private fun splitContentType(value: String?): Pair<String, String?> {
            if (value.isNullOrBlank()) return "application/octet-stream" to null
            val parts = value.split(';').map { it.trim() }
            val charset = parts.drop(1).firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')?.trim('"')
            return parts[0] to charset
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            502 -> "Bad Gateway"
            504 -> "Gateway Timeout"
            else -> if (status in 200..299) "OK" else "Error"
        }

        /** MIME type of the book file by extension; the reader decides by the `format`, this is for the Blob type. */
        fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "epub" -> "application/epub+zip"
            "mobi" -> "application/x-mobipocket-ebook"
            "azw3" -> "application/vnd.amazon.ebook"
            "fb2" -> "application/x-fictionbook+xml"
            "fbz" -> "application/zip"
            "cbz" -> "application/vnd.comicbook+zip"
            "cbr" -> "application/vnd.comicbook-rar"
            "cb7" -> "application/x-cb7"
            "cbt" -> "application/x-cbt"
            else -> "application/octet-stream"
        }

        /**
         * Parses a single `bytes=` range (RFC 9110). Multiple ranges and unknown units are ignored
         * (the whole file is served), a start behind the end of the file is unsatisfiable.
         */
        internal fun parseRange(header: String?, length: Long): RangeResult {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }
                ?.substring(6)?.trim() ?: return RangeResult.None
            if (spec.contains(',')) return RangeResult.None
            val dash = spec.indexOf('-')
            if (dash < 0) return RangeResult.None
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return RangeResult.None
                if (suffix <= 0 || length == 0L) return RangeResult.Unsatisfiable
                return RangeResult.Satisfiable((length - suffix).coerceAtLeast(0), length - 1)
            }
            val start = first.toLongOrNull() ?: return RangeResult.None
            val end = if (last.isEmpty()) length - 1 else (last.toLongOrNull() ?: return RangeResult.None)
            if (end < start) return RangeResult.None
            if (start >= length) return RangeResult.Unsatisfiable
            return RangeResult.Satisfiable(start, minOf(end, length - 1))
        }
    }
}

/** Reads [length] bytes of [file] from [start]; the file handle closes with the stream. */
private class FileSliceStream(file: File, start: Long, private var remaining: Long) : InputStream() {
    private val raf = RandomAccessFile(file, "r").also { it.seek(start) }

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = raf.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        raf.close()
    }
}
