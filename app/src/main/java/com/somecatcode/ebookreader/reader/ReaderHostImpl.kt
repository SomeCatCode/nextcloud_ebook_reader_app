package com.somecatcode.ebookreader.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/** Anything but these schemes is never handed to `ACTION_VIEW` (contract: http/https/mailto). */
fun isSafeExternalUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return url.contains(':') && scheme in setOf("http", "https", "mailto")
}

/**
 * Kotlin side of the reader WebView. [ReaderView] attaches a WebView ([attach]); messages from the
 * page arrive on a WebView binder thread through [bridge] and are decoded with [BridgeJson];
 * messages to the page are sent through `evaluateJavascript` on the main thread.
 *
 * Tap handling: the page turns pages on left/right taps itself, [ReaderToHost.Tap] therefore only
 * arrives for the center zone (toggle the app bars).
 *
 * Re-attaching a new WebView (e.g. after a configuration change) re-opens the book at the last known
 * position with the latest settings.
 *
 * @param proxy the request proxy of this reader (the screen calls `proxy.bind(...)` before [send]ing [HostToReader.Open])
 * @param runOnMain posts to the main thread (replaced in tests)
 */
class ReaderHostImpl(
    val proxy: ReaderRequestProxy,
    private val runOnMain: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) : ReaderHost {

    private val lock = Any()
    private val _events = MutableSharedFlow<ReaderToHost>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _ready = MutableStateFlow(false)
    private val _bookInfo = MutableStateFlow<BookInfo?>(null)
    private val _toc = MutableStateFlow<List<TocItem>>(emptyList())
    private val _lastRelocate = MutableStateFlow<ReaderToHost.Relocate?>(null)

    private var evaluator: ((String) -> Unit)? = null
    private val queue = ArrayDeque<HostToReader>()
    private var lastOpen: HostToReader.Open? = null
    private var settings: ReaderSettings? = null
    private var annotations: HostToReader.SetAnnotations? = null
    private val _selectionActions = MutableSharedFlow<SelectionAction>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var attachments = 0
    private var destroyed = false

    override val events: Flow<ReaderToHost> = _events.asSharedFlow()
    override val ready: Flow<Boolean> = _ready.asStateFlow()
    override val selectionActions: Flow<SelectionAction> = _selectionActions.asSharedFlow()

    /** Latest [ReaderToHost.Opened] info (null until the book is open). Survives late collectors. */
    val bookInfo: StateFlow<BookInfo?> = _bookInfo.asStateFlow()

    /** Latest table of contents. */
    val toc: StateFlow<List<TocItem>> = _toc.asStateFlow()

    /** Latest position (null before the first relocate). */
    val lastRelocate: StateFlow<ReaderToHost.Relocate?> = _lastRelocate.asStateFlow()

    val relocates: Flow<ReaderToHost.Relocate> get() = _events.filterIsInstance()
    val errors: Flow<ReaderToHost.Error> get() = _events.filterIsInstance()
    val externalLinks: Flow<String> get() = _events.filterIsInstance<ReaderToHost.ExternalLink>().map { it.url }
    val opened: Flow<ReaderToHost.Opened> get() = _events.filterIsInstance()

    /** The object to register with `addJavascriptInterface(bridge, "AndroidBridge")`. Only this object is exposed to JS. */
    val bridge: JsBridge = JsBridge()

    inner class JsBridge {
        /** Called by the page on a WebView binder thread. */
        @JavascriptInterface
        fun postMessage(json: String?) {
            onPageMessage(json)
        }
    }

    // ---- WebView side ------------------------------------------------------------------------

    /** A (new) WebView is ready to receive evaluated scripts. Resets [ready] until the page says `ready`. */
    fun attach(evaluate: (String) -> Unit) {
        synchronized(lock) {
            evaluator = evaluate
            attachments++
            _ready.value = false
        }
    }

    /** The WebView is gone. */
    fun detach() {
        synchronized(lock) {
            evaluator = null
            _ready.value = false
        }
    }

    /** Navigation the page attempted out of the book (e.g. a link): offered to the user like [ReaderToHost.ExternalLink]. */
    fun onNavigationBlocked(url: String) {
        if (isSafeExternalUrl(url)) {
            _events.tryEmit(ReaderToHost.ExternalLink(url))
        }
    }

    /** One of the app's entries of the text selection menu was tapped (main thread). */
    fun onSelectionAction(action: SelectionAction) {
        _selectionActions.tryEmit(action)
    }

    /** True while the open book supports highlights (the selection menu offers the app's entries). */
    val supportsAnnotations: Boolean get() = _bookInfo.value?.supportsAnnotations == true

    fun onRenderProcessGone() {
        detach()
        _events.tryEmit(ReaderToHost.Error(ErrorCode.READER, "The reader process stopped"))
    }

    /** Decodes one page message (any thread). Malformed messages become a reader error, never an exception. */
    internal fun onPageMessage(json: String?) {
        if (json == null || json.length > MAX_MESSAGE_CHARS) {
            _events.tryEmit(ReaderToHost.Error(ErrorCode.READER, "Invalid bridge message"))
            return
        }
        val message = try {
            BridgeJson.decodeFromString<ReaderToHost>(json)
        } catch (_: Exception) {
            _events.tryEmit(ReaderToHost.Error(ErrorCode.READER, "Malformed bridge message"))
            return
        }
        when (message) {
            is ReaderToHost.Ready -> onReady()
            is ReaderToHost.Opened -> _bookInfo.value = message.info
            is ReaderToHost.Toc -> _toc.value = message.items
            is ReaderToHost.Relocate -> _lastRelocate.value = message
            is ReaderToHost.ExternalLink -> if (!isSafeExternalUrl(message.url)) return
            else -> Unit
        }
        _events.tryEmit(message)
    }

    private fun onReady() {
        val toSend: List<HostToReader>
        synchronized(lock) {
            if (destroyed) return
            _ready.value = true
            toSend = buildList {
                val reopen = lastOpen
                if (attachments > 1 && reopen != null && queue.none { it is HostToReader.Open }) {
                    add(reopen.copy(initialLocator = _lastRelocate.value?.locator ?: reopen.initialLocator, settings = settings ?: reopen.settings))
                    // a new page knows no highlights yet
                    annotations?.takeIf { queue.none { it is HostToReader.SetAnnotations } }?.let(::add)
                }
                addAll(queue)
                queue.clear()
            }
        }
        toSend.forEach(::dispatch)
    }

    // ---- ReaderHost -------------------------------------------------------------------------

    override fun send(message: HostToReader) {
        synchronized(lock) {
            if (destroyed) return
            when (message) {
                is HostToReader.Open -> {
                    lastOpen = message
                    settings = message.settings
                    _bookInfo.value = null
                    _toc.value = emptyList()
                    _lastRelocate.value = null
                }
                is HostToReader.SetSettings -> settings = message.settings
                is HostToReader.SetAnnotations -> annotations = message
                HostToReader.Destroy -> {
                    lastOpen = null
                    annotations = null
                }
                else -> Unit
            }
            if (!_ready.value) {
                if (message is HostToReader.Open) queue.removeAll { it is HostToReader.Open }
                if (message is HostToReader.SetAnnotations) queue.removeAll { it is HostToReader.SetAnnotations }
                queue.addLast(message)
                return
            }
        }
        dispatch(message)
    }

    private fun dispatch(message: HostToReader) {
        val js = "EbookReaderHost.receive(" + BridgeJson.encodeToString<HostToReader>(message) + ")"
        runOnMain {
            val evaluate = synchronized(lock) { evaluator }
            evaluate?.invoke(js)
        }
    }

    override fun destroy() {
        val wasReady = _ready.value
        if (wasReady) dispatch(HostToReader.Destroy)
        synchronized(lock) {
            destroyed = true
            queue.clear()
            lastOpen = null
        }
        runOnMain { detach() }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val MAX_MESSAGE_CHARS = 2_000_000
    }
}
