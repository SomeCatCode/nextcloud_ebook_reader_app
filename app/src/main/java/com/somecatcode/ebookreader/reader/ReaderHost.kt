package com.somecatcode.ebookreader.reader

import kotlinx.coroutines.flow.Flow

/**
 * Kotlin side of the reader WebView, driven by the Reader screen (W-UI embeds it via
 * `ReaderView(host)`; W-READER provides the implementation `ReaderHostImpl` and the composable
 * `ReaderView`). WebView hardening (PLAN.md section 5): JavaScript enabled only for the bundled page,
 * `allowFileAccess = false`, `allowContentAccess = false`, no `file://`/`content://`, mixed content
 * blocked, `shouldOverrideUrlLoading` blocks navigation away from [READER_ORIGIN], safe browsing on,
 * `WebViewAssetLoader` for `/reader/` and the [ReaderRequestProxy] for `/api/`.
 */
interface ReaderHost {
    /** Events from the page, already decoded. Hot, replay 0; collect from the reader screen. */
    val events: Flow<ReaderToHost>

    /** True after [ReaderToHost.Ready]. */
    val ready: Flow<Boolean>

    /** Sends a message to the page (queued until [ready]). */
    fun send(message: HostToReader)

    /** Releases the WebView (call from the screen's dispose). */
    fun destroy()
}
