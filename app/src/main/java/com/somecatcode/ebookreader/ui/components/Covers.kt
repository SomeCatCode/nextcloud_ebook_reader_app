package com.somecatcode.ebookreader.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.somecatcode.ebookreader.AppContainer
import com.somecatcode.ebookreader.data.api.CoverSize
import com.somecatcode.ebookreader.data.api.EbookApi
import com.somecatcode.ebookreader.data.repo.LibraryBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException

/** Coil model for a book cover; resolved by [CoverFetcher] with the authenticated client of the account. */
data class CoverRequest(val accountId: String, val fileId: Long, val etag: String?, val large: Boolean = false) {
    val cacheKey: String get() = "cover:$accountId:$fileId:${if (large) "l" else "s"}:${etag.orEmpty()}"
}

/** Image loader for covers; null (previews, tests) makes [BookCover] draw the placeholder only. */
val LocalCoverLoader = compositionLocalOf<ImageLoader?> { null }

/**
 * Loads covers from the repository's cover source (`EbookApi.coverUrl` through the authenticated
 * client) and keeps them in `cacheDir/covers` so the library also shows them offline.
 */
class CoverFetcher(
    private val data: CoverRequest,
    private val api: suspend (accountId: String) -> EbookApi,
    private val dir: File,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = File(dir, data.cacheKey.hashCode().toUInt().toString(16) + "_${data.accountId.take(8)}_${data.fileId}")
        if (!file.exists()) {
            val client = api(data.accountId)
            val request = Request.Builder()
                .url(client.coverUrl(data.fileId, if (data.large) CoverSize.LARGE else CoverSize.SMALL))
                .build()
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                client.http.execute(request).use { response ->
                    if (!response.isSuccessful) throw IOException("Cover HTTP ${response.code}")
                    val tmp = File(dir, file.name + ".tmp")
                    response.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(file)) throw IOException("Cover cache write failed")
                }
            }
        }
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
    }

    class Factory(private val container: AppContainer, private val cacheDir: File) : Fetcher.Factory<CoverRequest> {
        override fun create(data: CoverRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(data, { container.apiClientFactory.forAccount(it) }, cacheDir)
    }

    object CoverKeyer : Keyer<CoverRequest> {
        override fun key(data: CoverRequest, options: Options): String = data.cacheKey
    }
}

fun createCoverImageLoader(context: Context, container: AppContainer): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(CoverFetcher.CoverKeyer)
            add(CoverFetcher.Factory(container, File(context.cacheDir, "covers")))
        }
        .build()

/** Cover of a book (2:3 area is decided by the caller); placeholder icon when there is no cover. */
@Composable
fun BookCover(
    accountId: String,
    fileId: Long,
    hasCover: Boolean,
    etag: String?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val loader = LocalCoverLoader.current
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.AutoStories,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        if (hasCover && loader != null) {
            AsyncImage(
                model = CoverRequest(accountId, fileId, etag, large),
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun BookCover(book: LibraryBook, modifier: Modifier = Modifier, large: Boolean = false) =
    BookCover(book.key.accountId, book.key.fileId, book.hasCover, book.coverEtag, modifier, large)
