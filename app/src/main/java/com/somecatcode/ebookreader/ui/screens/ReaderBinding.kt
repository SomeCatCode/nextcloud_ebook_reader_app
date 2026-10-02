package com.somecatcode.ebookreader.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.somecatcode.ebookreader.AppContainer
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.reader.BookSource
import com.somecatcode.ebookreader.reader.EbookApiReaderBackend
import com.somecatcode.ebookreader.reader.HostToReader
import com.somecatcode.ebookreader.reader.ReaderHost
import com.somecatcode.ebookreader.reader.ReaderHostImpl
import com.somecatcode.ebookreader.reader.ReaderRequestProxyImpl
import com.somecatcode.ebookreader.reader.ReaderView
import kotlinx.coroutines.runBlocking

/*
 * The only place where the reader screen touches the WebView implementation in `reader/`.
 */

/**
 * Creates the host for one reader session of [key]. The proxy is bound to the book right before
 * [HostToReader.Open] goes out: offline ([BookSource.File]) when a finished download exists,
 * otherwise online through the account's API client.
 */
@Suppress("UNUSED_PARAMETER")
fun createReaderHost(context: Context, container: AppContainer, key: BookKey): ReaderHost {
    val proxy = ReaderRequestProxyImpl(
        // Both lambdas run on the WebView IO thread, blocking is fine there.
        backendFor = { accountId, fileId ->
            runBlocking {
                val account = container.database.accountDao().get(accountId) ?: return@runBlocking null
                val book = container.database.bookDao().get(accountId, fileId) ?: return@runBlocking null
                val api = runCatching { container.apiClientFactory.forAccount(accountId) }.getOrNull() ?: return@runBlocking null
                EbookApiReaderBackend(api, fileId, account.userId, book.path)
            }
        },
        localFile = { accountId, fileId -> runBlocking { container.downloadRepository.localFile(BookKey(accountId, fileId)) } },
    )
    return BindingReaderHost(ReaderHostImpl(proxy), key)
}

/** The WebView of the reader, bound to [host]. */
@Composable
fun ReaderSurface(host: ReaderHost, modifier: Modifier = Modifier) {
    ReaderView((host as BindingReaderHost).impl, modifier)
}

/** Binds the request proxy to the book before every [HostToReader.Open]. */
class BindingReaderHost(val impl: ReaderHostImpl, private val key: BookKey) : ReaderHost by impl {
    override fun send(message: HostToReader) {
        if (message is HostToReader.Open) impl.proxy.bind(key.accountId, key.fileId, online = message.source !is BookSource.File)
        impl.send(message)
    }
}
