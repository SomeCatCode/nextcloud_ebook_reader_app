package com.somecatcode.ebookreader.reader

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Request proxy of the reader WebView. The page only ever requests relative/`appassets` URLs;
 * Kotlin answers them from the offline file or forwards them with authentication to the server.
 * JavaScript never sees credentials.
 *
 * URL scheme (all `GET`, origin [READER_ORIGIN] = `https://appassets.androidplatform.net`):
 *
 * | URL                                  | Offline answer (downloaded book)        | Online answer (server)                                  |
 * |--------------------------------------|-----------------------------------------|---------------------------------------------------------|
 * | `/reader/` (all files)                         | assets/reader (WebViewAssetLoader)      | same                                                    |
 * | `/api/book`                          | local file, `Range` honoured (206)      | WebDAV `GET /remote.php/dav/files/{userId}/{path}` + Range |
 * | `/api/archive/entries`               | built from the local zip's directory    | `GET /apps/ebookreader/archive/{id}/entries`           |
 * | `/api/item?id=<entry>`               | entry bytes read from the local zip     | `GET /apps/ebookreader/item/{id}?id=<entry>`           |
 * | `/api/comic/pages`                   | page list from the local archive        | `GET /apps/ebookreader/comic/{id}/pages`               |
 * | `/api/comic/page/{index}?w=<px>`     | page bytes from the local archive (CBR/CB7 need the whole file anyway) | `GET /apps/ebookreader/comic/{id}/page/{index}?w=` |
 *
 * The proxy is bound to ONE book (account + file id) by [bind] when the reader screen opens it; there is
 * no way for the page to address another account or file. Everything else under the origin is answered
 * with 404, and requests to any other origin are blocked (`null` return = WebView default is NOT used,
 * the proxy returns a 403 response for foreign hosts).
 *
 * Mapping of failures: network error -> 504 + header `X-Reader-Error: network`; 401 from the server ->
 * 401 + `X-Reader-Error: unauthorized`. The page converts those into [ReaderToHost.Error].
 *
 * Owner: W-READER (`reader/ReaderRequestProxyImpl.kt`, uses `EbookApi.http`, `DownloadManager.localFile`).
 */
interface ReaderRequestProxy {
    /** Selects the book served under `/api/`. [online] decides between offline file and server. */
    fun bind(accountId: String, fileId: Long, online: Boolean)

    /** Called from `WebViewClient.shouldInterceptRequest` (WebView IO thread, blocking is fine). Returns null for `/reader/` (all files) so that WebViewAssetLoader answers. */
    fun intercept(request: WebResourceRequest): WebResourceResponse?
}
