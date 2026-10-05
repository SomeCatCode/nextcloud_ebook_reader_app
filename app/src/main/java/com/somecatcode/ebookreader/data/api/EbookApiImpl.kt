package com.somecatcode.ebookreader.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * [EbookApi] for one account over OkHttp + kotlinx.serialization. Verified against the server
 * (`lib/Controller`, `openapi.json`): OCS endpoints below `/ocs/v2.php/apps/ebookreader/api/v1`,
 * content endpoints below `/index.php/apps/ebookreader`.
 */
class EbookApiImpl(
    serverUrl: String,
    loginName: String,
    appPassword: String,
    baseClient: OkHttpClient,
    private val json: Json = ApiJson,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EbookApi {

    private val server: HttpUrl = (serverUrl.trimEnd('/') + "/").toHttpUrl()
    private val authed = AuthenticatedHttpImpl(baseClient, server, loginName, appPassword)

    override val http: AuthenticatedHttp get() = authed

    // ---- URL helpers --------------------------------------------------------------------------------

    private fun base(vararg segments: String): HttpUrl.Builder {
        val b = server.newBuilder()
        segments.forEach { s -> s.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) } }
        return b
    }

    private fun ocsUrl(path: String): HttpUrl.Builder =
        base("ocs/v2.php/apps/ebookreader/api/v1", path).addQueryParameter("format", "json")

    private fun contentUrl(path: String): HttpUrl.Builder = base("index.php/apps/ebookreader", path)

    // ---- core -------------------------------------------------------------------------------------

    private suspend fun execute(request: Request): Response = withContext(io) {
        try {
            authed.await(request)
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
    }

    /** Executes an OCS request and decodes `ocs.data` with [serializer]. */
    private suspend fun <T> ocs(
        method: String,
        url: HttpUrl,
        serializer: KSerializer<T>,
        body: JsonElement? = null,
    ): T {
        execute(jsonRequest(method, url, body)).use { r ->
            val text = readBody(r)
            if (!r.isSuccessful) throw mapHttpError(r.code, r.header("Retry-After"), text)
            return decodeEnvelope(r.code, text, serializer)
        }
    }

    private fun <T> decodeEnvelope(code: Int, text: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(OcsEnvelope.serializer(serializer), text).ocs.data
    } catch (e: SerializationException) {
        throw ApiException.Server(code, "Unexpected response: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ApiException.Server(code, "Unexpected response: ${e.message}")
    }

    private fun jsonRequest(method: String, url: HttpUrl, body: JsonElement?): Request {
        val b = Request.Builder().url(url).header("Accept", "application/json").header("OCS-APIRequest", "true")
        val requestBody: RequestBody? = when {
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_MEDIA)
            method == "GET" || method == "HEAD" || method == "DELETE" -> null
            else -> "".toRequestBody(JSON_MEDIA)
        }
        return b.method(method, requestBody).build()
    }

    private suspend fun readBody(r: Response): String = withContext(io) {
        try {
            r.body.string()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
    }

    private suspend fun <T> getOcs(path: String, serializer: KSerializer<T>, params: HttpUrl.Builder.() -> Unit = {}): T =
        ocs("GET", ocsUrl(path).apply(params).build(), serializer)

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement =
        json.encodeToJsonElement(serializer, value)

    // ---- server probe -------------------------------------------------------------------------------

    override suspend fun capabilities(): CapabilitiesResponse = ocs(
        "GET",
        base("ocs/v2.php/cloud/capabilities").addQueryParameter("format", "json").build(),
        CapabilitiesResponse.serializer(),
    )

    override suspend fun currentUser(): CloudUser = ocs(
        "GET",
        base("ocs/v2.php/cloud/user").addQueryParameter("format", "json").build(),
        CloudUser.serializer(),
    )

    override suspend fun checkCompatibility(): ServerCompatibility {
        val caps = capabilities()
        val eb = caps.capabilities.ebookreader ?: return ServerCompatibility.AppMissing
        return try {
            series()
            ServerCompatibility.Ok(eb, caps.version?.string.orEmpty())
        } catch (e: ApiException.NotFound) {
            ServerCompatibility.AppTooOld
        }
    }

    // ---- library --------------------------------------------------------------------------------------

    private fun HttpUrl.Builder.applyQuery(q: BookQuery, withPaging: Boolean) {
        q.search?.takeIf { it.isNotBlank() }?.let { addQueryParameter("search", it) }
        q.include.forEach { addQueryParameter("include[]", "${it.type.wire}:${it.name}") }
        q.exclude.forEach { addQueryParameter("exclude[]", "${it.type.wire}:${it.name}") }
        if (q.match != MatchMode.ALL) addQueryParameter("match", q.match.wire)
        q.status?.let { addQueryParameter("status", it.wire()) }
        addQueryParameter("sort", q.sort.wire)
        addQueryParameter("order", if (q.descending) "desc" else "asc")
        q.inSeries?.let { addQueryParameter("inSeries", if (it) "1" else "0") }
        if (withPaging) {
            addQueryParameter("limit", q.limit.coerceIn(1, 200).toString())
            addQueryParameter("offset", q.offset.coerceAtLeast(0).toString())
        }
    }

    override suspend fun books(query: BookQuery): BookListDto =
        getOcs("books", BookListDto.serializer()) { applyQuery(query, true) }

    override suspend fun book(fileId: Long): BookDto = getOcs("books/$fileId", BookDto.serializer())

    override suspend fun sync(cursor: String): SyncDto =
        getOcs("sync", SyncDto.serializer()) { addQueryParameter("cursor", cursor) }

    override suspend fun facets(): FacetsDto = getOcs("facets", FacetsDto.serializer())

    override suspend fun series(query: BookQuery): List<SeriesDto> =
        getOcs("series", SeriesListDto.serializer()) { applyQuery(query, false) }.series

    override suspend fun shelves(): List<ShelfDto> = getOcs("shelves", ShelvesDto.serializer()).shelves

    override suspend fun createShelf(name: String, query: SmartQueryDto?): ShelfDto {
        val fields = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(name), "type" to JsonPrimitive(if (query == null) "manual" else "smart"))
        query?.let { fields["query"] = encode(SmartQueryDto.serializer(), it) }
        return ocs("POST", ocsUrl("shelves").build(), ShelfDto.serializer(), JsonObject(fields))
    }

    override suspend fun updateShelf(id: Long, name: String?, query: SmartQueryDto?, sortOrder: Int?): ShelfDto {
        val fields = linkedMapOf<String, JsonElement>()
        name?.let { fields["name"] = JsonPrimitive(it) }
        query?.let { fields["query"] = encode(SmartQueryDto.serializer(), it) }
        sortOrder?.let { fields["sortOrder"] = JsonPrimitive(it) }
        return ocs("PATCH", ocsUrl("shelves/$id").build(), ShelfDto.serializer(), JsonObject(fields))
    }

    override suspend fun deleteShelf(id: Long) {
        ocs("DELETE", ocsUrl("shelves/$id").build(), JsonElement.serializer())
    }

    override suspend fun addToShelf(id: Long, fileIds: List<Long>) {
        for (chunk in fileIds.chunked(MAX_SHELF_IDS)) {
            ocs("POST", ocsUrl("shelves/$id/books").build(), JsonElement.serializer(), fileIdsBody(chunk))
        }
    }

    override suspend fun removeFromShelf(id: Long, fileIds: List<Long>) {
        for (chunk in fileIds.chunked(MAX_SHELF_IDS)) {
            ocs("DELETE", ocsUrl("shelves/$id/books").build(), JsonElement.serializer(), fileIdsBody(chunk))
        }
    }

    private fun fileIdsBody(ids: List<Long>): JsonElement =
        JsonObject(mapOf("fileIds" to kotlinx.serialization.json.JsonArray(ids.map { JsonPrimitive(it) })))

    override suspend fun recentBooks(limit: Int): List<BookDto> =
        getOcs("progress/recent", RecentBooksDto.serializer()) {
            addQueryParameter("limit", limit.coerceIn(1, 50).toString())
        }.books

    // ---- progress -------------------------------------------------------------------------------------

    override suspend fun progress(fileId: Long): ProgressDto? =
        getOcs("progress/$fileId", ProgressDto.serializer().nullable)

    override suspend fun putProgress(fileId: Long, body: ProgressPutRequest): ProgressPutResult {
        val request = jsonRequest("PUT", ocsUrl("progress/$fileId").build(), encode(ProgressPutRequest.serializer(), body))
        execute(request).use { r ->
            val text = readBody(r)
            if (r.code == 409) {
                return ProgressPutResult.Conflict(decodeEnvelope(r.code, text, ProgressConflictDto.serializer()).current)
            }
            if (!r.isSuccessful) throw mapHttpError(r.code, r.header("Retry-After"), text)
            return ProgressPutResult.Stored(decodeEnvelope(r.code, text, ProgressDto.serializer()))
        }
    }

    override suspend fun putProgressBatch(items: List<ProgressBatchItem>): List<ProgressBatchResultItem> {
        val out = ArrayList<ProgressBatchResultItem>(items.size)
        for (chunk in items.chunked(MAX_BATCH)) {
            val body = encode(ProgressBatchRequest.serializer(), ProgressBatchRequest(chunk))
            out += ocs("POST", ocsUrl("progress/batch").build(), ProgressBatchResult.serializer(), body).results
        }
        return out
    }

    // ---- annotations ----------------------------------------------------------------------------------

    override suspend fun annotations(fileId: Long): List<AnnotationDto> =
        getOcs("books/$fileId/annotations", AnnotationListDto.serializer()).annotations

    override suspend fun upsertAnnotation(fileId: Long, body: AnnotationUpsertRequest): AnnotationWriteResult =
        annotationWrite("POST", ocsUrl("books/$fileId/annotations").build(), encode(AnnotationUpsertRequest.serializer(), body))

    override suspend fun patchAnnotation(uuid: String, patch: AnnotationPatchRequest): AnnotationWriteResult =
        annotationWrite("PATCH", ocsUrl("annotations/${annotationId(uuid)}").build(), encode(AnnotationPatchRequest.serializer(), patch))

    override suspend fun deleteAnnotation(uuid: String, clientUpdatedAt: Long): AnnotationWriteResult =
        annotationWrite(
            "DELETE",
            ocsUrl("annotations/${annotationId(uuid)}").addQueryParameter("clientUpdatedAt", clientUpdatedAt.toString()).build(),
            null,
        )

    /** Only UUIDs go into the path (the server route accepts nothing else). */
    private fun annotationId(uuid: String): String {
        if (!UUID_PATTERN.matches(uuid)) throw ApiException.BadRequest("Invalid annotation id")
        return uuid.lowercase()
    }

    private suspend fun annotationWrite(method: String, url: HttpUrl, body: JsonElement?): AnnotationWriteResult {
        execute(jsonRequest(method, url, body)).use { r ->
            val text = readBody(r)
            if (r.code == 409) {
                return AnnotationWriteResult.Conflict(decodeEnvelope(r.code, text, AnnotationConflictDto.serializer()).current)
            }
            if (!r.isSuccessful) throw mapHttpError(r.code, r.header("Retry-After"), text)
            return AnnotationWriteResult.Stored(decodeEnvelope(r.code, text, AnnotationDto.serializer()))
        }
    }

    // ---- editing --------------------------------------------------------------------------------------

    override suspend fun patchAppData(fileId: Long, patch: AppDataPatch): BookDto {
        val fields = LinkedHashMap<String, JsonElement>()
        if (patch.setRating) fields["rating"] = patch.rating?.let { JsonPrimitive(it) } ?: JsonNull
        patch.readStatus?.let { fields["readStatus"] = JsonPrimitive(it.wire()) }
        return ocs("PATCH", ocsUrl("books/$fileId/app-data").build(), BookDto.serializer(), JsonObject(fields))
    }

    override suspend fun patchMetadata(fileId: Long, patch: MetadataPatch): SaveResultDto =
        ocs("PATCH", ocsUrl("books/$fileId/metadata").build(), SaveResultDto.serializer(), JsonObject(patch.fields))

    // ---- non-OCS content ---------------------------------------------------------------------------------

    private suspend fun <T> getContent(url: HttpUrl, serializer: KSerializer<T>): T {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        execute(request).use { r ->
            val text = readBody(r)
            if (!r.isSuccessful) throw mapHttpError(r.code, r.header("Retry-After"), text)
            return try {
                json.decodeFromString(serializer, text)
            } catch (e: SerializationException) {
                throw ApiException.Server(r.code, "Unexpected response: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw ApiException.Server(r.code, "Unexpected response: ${e.message}")
            }
        }
    }

    override suspend fun comicPages(fileId: Long): ComicPagesDto =
        getContent(contentUrl("comic/$fileId/pages").build(), ComicPagesDto.serializer())

    override suspend fun archiveEntries(fileId: Long): ArchiveEntriesDto =
        getContent(contentUrl("archive/$fileId/entries").build(), ArchiveEntriesDto.serializer())

    // ---- URL builders ------------------------------------------------------------------------------------

    override fun coverUrl(fileId: Long, size: CoverSize): HttpUrl =
        contentUrl("cover/$fileId").addQueryParameter("size", size.wire).build()

    override fun comicPageUrl(fileId: Long, index: Int, width: Int): HttpUrl {
        val b = contentUrl("comic/$fileId/page/$index")
        if (width > 0) b.addQueryParameter("w", width.toString())
        return b.build()
    }

    override fun itemUrl(fileId: Long, entry: String): HttpUrl =
        contentUrl("item/$fileId").addQueryParameter("id", entry).build()

    override fun davFileUrl(userId: String, path: String): HttpUrl {
        val b = server.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(userId)
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    // ---- account -----------------------------------------------------------------------------------------

    override suspend fun revokeAppPassword() {
        val url = base("ocs/v2.php/core/apppassword").addQueryParameter("format", "json").build()
        execute(jsonRequest("DELETE", url, null)).use { r ->
            val text = readBody(r)
            if (!r.isSuccessful) throw mapHttpError(r.code, r.header("Retry-After"), text)
        }
    }

    private companion object {
        const val MAX_BATCH = 100
        const val MAX_SHELF_IDS = 500
        val UUID_PATTERN = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

/** Wire value of a [ReadStatus] (`unread|reading|finished`). */
fun ReadStatus.wire(): String = when (this) {
    ReadStatus.UNREAD -> "unread"
    ReadStatus.READING -> "reading"
    ReadStatus.FINISHED -> "finished"
}
