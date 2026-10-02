package com.somecatcode.ebookreader.data.download

/**
 * Pure decisions of the resumable WebDAV download (unit-tested without I/O).
 *
 * - A partial file is only resumed when a strong ETag of the first response is known: the request
 *   carries `Range: bytes=<size>-` and `If-Range: <etag>`. If the file changed the server answers
 *   200 with the whole file and we restart.
 * - Without a known ETag, a partial file cannot be validated and is discarded.
 */
object ResumePolicy {

    /** What to send. [rangeFrom] null = plain GET of the whole file; [discardPart] = delete the .part first. */
    data class RequestPlan(val rangeFrom: Long?, val ifRange: String?, val discardPart: Boolean)

    sealed interface ResponseAction {
        /** Append the body to the .part file (server honoured the range). */
        data object Append : ResponseAction
        /** Truncate the .part file and write the body from byte 0. */
        data object Restart : ResponseAction
        /** The partial file already has the full length. */
        data object AlreadyComplete : ResponseAction
        /** Not usable (unexpected status or a Content-Range that does not match the request). */
        data class Fail(val retryable: Boolean) : ResponseAction
    }

    fun plan(partSize: Long, savedEtag: String?, expectedTotal: Long): RequestPlan {
        val etag = usableEtag(savedEtag)
        return when {
            partSize <= 0L -> RequestPlan(null, null, discardPart = false)
            etag == null -> RequestPlan(null, null, discardPart = true)
            expectedTotal > 0 && partSize > expectedTotal -> RequestPlan(null, null, discardPart = true)
            else -> RequestPlan(partSize, etag, discardPart = false)
        }
    }

    /**
     * @param requestedFrom start offset of the sent `Range` (null if none was sent)
     * @param contentRangeStart start offset of the response `Content-Range` (null if absent)
     */
    fun interpret(
        statusCode: Int,
        requestedFrom: Long?,
        contentRangeStart: Long?,
        partSize: Long,
        expectedTotal: Long,
    ): ResponseAction = when (statusCode) {
        200 -> ResponseAction.Restart
        206 -> if (requestedFrom != null && contentRangeStart == requestedFrom) ResponseAction.Append else ResponseAction.Restart
        416 -> if (partSize > 0 && expectedTotal > 0 && partSize == expectedTotal) ResponseAction.AlreadyComplete else ResponseAction.Restart
        408, 425, 429, 500, 502, 503, 504 -> ResponseAction.Fail(retryable = true)
        else -> ResponseAction.Fail(retryable = false)
    }

    /** Weak validators (`W/"..."`) cannot be used for `If-Range`. */
    fun usableEtag(etag: String?): String? = etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

    /** Parses `Content-Range: bytes 100-999/1000` -> (start, total). */
    fun parseContentRange(header: String?): Pair<Long, Long?>? {
        if (header == null) return null
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).find(header) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        return start to m.groupValues[3].toLongOrNull()
    }
}
