package com.somecatcode.ebookreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import java.io.ByteArrayInputStream

/** URL of the bundled reader page. */
const val READER_PAGE_URL = "$READER_ORIGIN/reader/index.html"

/**
 * The reader WebView. [host] must be a [ReaderHostImpl]; send it [HostToReader.Open] (after binding
 * its proxy) and collect its flows. Hardened as described in docs/CONTRACTS.md section 6.
 *
 * @param volumeKeysTurnPages when true the volume keys (while the reader has focus) turn pages:
 * volume down = next, volume up = previous
 * @param backgroundColor ARGB shown before the page paints (use the theme background to avoid a flash)
 */
@Composable
fun ReaderView(
    host: ReaderHost,
    modifier: Modifier = Modifier,
    volumeKeysTurnPages: Boolean = false,
    backgroundColor: Int = 0,
) {
    val impl = host as? ReaderHostImpl ?: error("ReaderView needs a ReaderHostImpl")
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webViewHolder[0]?.onPause()
                Lifecycle.Event.ON_RESUME -> webViewHolder[0]?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            createReaderWebView(context, impl, volumeKeysTurnPages, backgroundColor).also { webViewHolder[0] = it }
        },
        update = { view ->
            view.setOnKeyListener(if (volumeKeysTurnPages) volumeKeyListener(impl) else null)
        },
        onRelease = { view ->
            webViewHolder[0] = null
            impl.detach()
            view.stopLoading()
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.webViewClient = WebViewClient()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        },
    )
}

private const val BRIDGE_NAME = "AndroidBridge"

private fun volumeKeyListener(host: ReaderHostImpl) = android.view.View.OnKeyListener { _, keyCode, event ->
    if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
        false
    } else {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            host.send(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) HostToReader.Next else HostToReader.Prev)
        }
        true // consume down and up: no system volume UI
    }
}

/** Serves the bundled `assets/reader/` below `/reader/` with correct MIME types (wasm). */
private class ReaderAssetsHandler(context: Context) : WebViewAssetLoader.PathHandler {
    private val assets = AssetsPathHandler(context)

    override fun handle(path: String): WebResourceResponse? {
        if (path.contains("..")) return null
        val response = assets.handle("reader/$path") ?: return null
        if (path.endsWith(".wasm")) {
            return WebResourceResponse("application/wasm", null, response.data)
        }
        return response
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun createReaderWebView(
    context: Context,
    host: ReaderHostImpl,
    volumeKeysTurnPages: Boolean,
    backgroundColor: Int,
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(ReaderRequestProxyImpl.READER_HOST)
        .addPathHandler("/reader/", ReaderAssetsHandler(context))
        .build()

    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    webView.setBackgroundColor(backgroundColor)
    webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
    webView.isFocusable = true
    webView.isFocusableInTouchMode = true

    with(webView.settings) {
        javaScriptEnabled = true // only ever the bundled page: navigation away is blocked below
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
        textZoom = 100
        builtInZoomControls = false
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
    }

    webView.addJavascriptInterface(host.bridge, BRIDGE_NAME)
    host.attach { js -> webView.evaluateJavascript(js, null) }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val proxied = host.proxy.intercept(request)
            if (proxied != null) return proxied
            // /reader/...: bundled assets; anything the loader does not know must never hit the network
            return assetLoader.shouldInterceptRequest(request.url) ?: notFound()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val readerPage = url.scheme == "https" && url.host == ReaderRequestProxyImpl.READER_HOST &&
                url.encodedPath?.startsWith("/reader/") == true
            val inert = url.scheme == "blob" || url.toString() == "about:blank"
            if (readerPage || inert) return false // the bundled page and its blob: section frames
            if (request.isForMainFrame) host.onNavigationBlocked(url.toString())
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (volumeKeysTurnPages) view.requestFocus()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            host.onRenderProcessGone()
            return true
        }
    }
    if (volumeKeysTurnPages) webView.setOnKeyListener(volumeKeyListener(host))
    webView.loadUrl(READER_PAGE_URL)
    return webView
}

private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
