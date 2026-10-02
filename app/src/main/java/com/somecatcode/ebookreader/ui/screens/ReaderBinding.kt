package com.somecatcode.ebookreader.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.somecatcode.ebookreader.AppContainer
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.reader.HostToReader
import com.somecatcode.ebookreader.reader.ReaderHost
import com.somecatcode.ebookreader.reader.ReaderToHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * The ONLY place where the reader screen touches W-READER's implementation. The WebView host
 * (`ReaderHostImpl`) and the composable `ReaderView(host)` live in `reader/` and are not part of this
 * branch, so this file provides placeholders. After merging W-READER, replace the bodies:
 *
 *   fun createReaderHost(context, container, key): ReaderHost = ReaderHostImpl(context, container, key)
 *   @Composable fun ReaderSurface(host, modifier) { ReaderView(host, modifier) }
 */

/** Creates the host for one reader session of [key]. The host binds the request proxy to the book. */
@Suppress("UNUSED_PARAMETER")
fun createReaderHost(context: Context, container: AppContainer, key: BookKey): ReaderHost = PlaceholderReaderHost()

/** The WebView of the reader, bound to [host]. */
@Composable
fun ReaderSurface(host: ReaderHost, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val unused = host
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.reader_placeholder), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Host without a WebView: swallows messages, never emits events. */
class PlaceholderReaderHost : ReaderHost {
    private val eventFlow = MutableSharedFlow<ReaderToHost>()
    override val events: Flow<ReaderToHost> = eventFlow.asSharedFlow()
    override val ready: Flow<Boolean> = MutableStateFlow(false)
    override fun send(message: HostToReader) = Unit
    override fun destroy() = Unit
}
