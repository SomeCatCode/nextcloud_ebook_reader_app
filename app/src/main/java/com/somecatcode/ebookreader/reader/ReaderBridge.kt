package com.somecatcode.ebookreader.reader

import com.somecatcode.ebookreader.data.api.Locator
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Reader bridge protocol, version 1 (binding, see docs/CONTRACTS.md section 6).
 *
 * Transport
 *  - Kotlin -> JS: `webView.evaluateJavascript("EbookReaderHost.receive(<json>)", null)`; <json> is a
 *    [HostToReader] message serialised with [BridgeJson] (discriminator property `type`).
 *  - JS -> Kotlin: `AndroidBridge.postMessage(jsonString)` where `AndroidBridge` is an object with one
 *    `@JavascriptInterface fun postMessage(String)` added with `addJavascriptInterface`; the string is a
 *    [ReaderToHost] message. The method runs on a WebView binder thread: forward to the main thread/Flow.
 *  - The page is loaded from `https://appassets.androidplatform.net/reader/index.html`
 *    (WebViewAssetLoader, assets/reader). JS sends [ReaderToHost.Ready] when `EbookReaderHost` exists;
 *    Kotlin must not call `receive` before that.
 */

val BridgeJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

const val BRIDGE_PROTOCOL_VERSION = 1

/** Origin served by WebViewAssetLoader (assets and the request proxy). */
const val READER_ORIGIN = "https://appassets.androidplatform.net"

/** Messages Kotlin sends to the reader page. */
@Serializable
sealed interface HostToReader {

    /**
     * Opens a book. The JS side builds the `ReaderSource` of reader-core from [source]:
     *  - [BookSource.File]: `fetch(source.url)` -> Blob -> `File` (offline copy or whole-file fallback);
     *  - [BookSource.RemoteZip]: `fetch(entriesUrl)` -> `{etag, entries}`, entries via `itemUrl + encodeURIComponent(name)`;
     *  - [BookSource.RemoteComic]: `fetch(pagesUrl)` -> `{etag, pages}`, pages via `pageUrl` with `{index}` and `{width}` replaced.
     * All URLs are below `/api/` and are answered by [ReaderRequestProxy]. Then `createReader(...).open(source, format, initialLocator)`.
     */
    @Serializable
    @SerialName("open")
    data class Open(
        val book: BookRef,
        val source: BookSource,
        /** Position to start at (local progress or the one the user chose in the conflict dialog); null = start. */
        val initialLocator: Locator? = null,
        val settings: ReaderSettings = ReaderSettings(),
    ) : HostToReader

    /** Jump to a TOC entry (`href`) or locator. */
    @Serializable
    @SerialName("goTo")
    data class GoTo(val locator: Locator? = null, val href: String? = null) : HostToReader

    @Serializable
    @SerialName("next")
    data object Next : HostToReader

    @Serializable
    @SerialName("prev")
    data object Prev : HostToReader

    /** Live settings change (theme, typography, layout). */
    @Serializable
    @SerialName("setSettings")
    data class SetSettings(val settings: ReaderSettings) : HostToReader

    /**
     * Replaces the highlights drawn into the book (reflowable books only; ignored for comics and fixed
     * layouts). Sent whenever the annotations of the book change; the page keeps the list across re-opens.
     * Only the CFI, color and "has a note" flag reach the page, never the text of a note.
     */
    @Serializable
    @SerialName("setAnnotations")
    data class SetAnnotations(val annotations: List<DrawnAnnotation>) : HostToReader

    /** Removes the text selection in the book (after a highlight was made from it). */
    @Serializable
    @SerialName("clearSelection")
    data object ClearSelection : HostToReader

    /** Tears the reader down (the page releases workers and object URLs). */
    @Serializable
    @SerialName("destroy")
    data object Destroy : HostToReader
}

/** Messages the reader page sends to Kotlin. */
@Serializable
sealed interface ReaderToHost {

    /** The bundle is loaded and `EbookReaderHost.receive` is callable. */
    @Serializable
    @SerialName("ready")
    data class Ready(val protocol: Int = BRIDGE_PROTOCOL_VERSION) : ReaderToHost

    /** The book is open (after [HostToReader.Open]). */
    @Serializable
    @SerialName("opened")
    data class Opened(val info: BookInfo) : ReaderToHost

    /** Position changed (every page turn/scroll; Kotlin debounces before saving). */
    @Serializable
    @SerialName("relocate")
    data class Relocate(
        val locator: Locator,
        /** Overall progress 0..1. */
        val percentage: Double,
        /** Chapter or page label, if known. */
        val label: String? = null,
        val page: PageInfo? = null,
    ) : ReaderToHost

    /** Table of contents, sent once after [Opened]. */
    @Serializable
    @SerialName("toc")
    data class Toc(val items: List<TocItem>) : ReaderToHost

    /** A link points outside of the book. Kotlin asks the user, then starts an `ACTION_VIEW` intent (https/http/mailto only). */
    @Serializable
    @SerialName("externalLink")
    data class ExternalLink(val url: String) : ReaderToHost

    /** Tap on the page: left/center/right zone (center toggles the app bars). */
    @Serializable
    @SerialName("tap")
    data class Tap(val zone: String) : ReaderToHost

    /**
     * The user selected text (reflowable books, debounced by reader-core). [locator] is the locator of the
     * selection with the range CFI in `locations.cfi`; [rect] is in CSS px relative to the page (= dp).
     */
    @Serializable
    @SerialName("selection")
    data class Selection(val text: String, val cfi: String, val locator: Locator, val rect: SelectionRect) : ReaderToHost

    /** The selection is gone (collapsed, tapped elsewhere, page turned). */
    @Serializable
    @SerialName("selectionClear")
    data object SelectionClear : ReaderToHost

    /** The user tapped a drawn highlight ([id] = annotation uuid); the tap turned no page. */
    @Serializable
    @SerialName("annotationClick")
    data class AnnotationClick(val id: String, val rect: SelectionRect) : ReaderToHost

    @Serializable
    @SerialName("error")
    data class Error(val code: ErrorCode, val message: String) : ReaderToHost
}

/** A highlight for the page: reader-core `ReaderAnnotation`. */
@Serializable
data class DrawnAnnotation(
    /** Annotation uuid, returned with [ReaderToHost.AnnotationClick]. */
    val id: String,
    /** Range CFI of the text. */
    val cfi: String,
    /** `yellow|green|blue|pink|purple`, null = yellow. */
    val color: String? = null,
    /** Drawn with an additional underline. */
    val hasNote: Boolean = false,
)

/** Rectangle in CSS px relative to the reader page (the WebView fills the screen, so CSS px = dp). */
@Serializable
data class SelectionRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

@Serializable
data class BookRef(
    val accountId: String,
    val fileId: Long,
    /** `epub|mobi|azw3|fb2|fbz|cbz|cbr|cb7|cbt` */
    val format: String,
    val title: String? = null,
)

@Serializable
sealed interface BookSource {
    /** Whole file at [url] (default `/api/book`), answered with Range support. */
    @Serializable
    @SerialName("file")
    data class File(val url: String = "$READER_ORIGIN/api/book", val fileName: String) : BookSource

    @Serializable
    @SerialName("remote-zip")
    data class RemoteZip(
        val name: String,
        val entriesUrl: String = "$READER_ORIGIN/api/archive/entries",
        /** Entry URL is `itemUrl` + percent-encoded entry name. */
        val itemUrl: String = "$READER_ORIGIN/api/item?id=",
    ) : BookSource

    @Serializable
    @SerialName("remote-comic")
    data class RemoteComic(
        val name: String,
        val pagesUrl: String = "$READER_ORIGIN/api/comic/pages",
        /** Placeholders `{index}` (0-based) and `{width}` (device pixels). */
        val pageUrl: String = "$READER_ORIGIN/api/comic/page/{index}?w={width}",
    ) : BookSource
}

@Serializable
data class PageInfo(val current: Int, val total: Int)

@Serializable
data class TocItem(val label: String, val href: String, val subitems: List<TocItem> = emptyList())

@Serializable
data class BookInfo(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val isComic: Boolean = false,
    val fixedLayout: Boolean = false,
    val rtl: Boolean = false,
    val pageCount: Int = 0,
    /** Text selection and highlights work with this book (reflowable text, not comics or fixed layouts). */
    val supportsAnnotations: Boolean = false,
)

@Serializable
enum class ErrorCode {
    @SerialName("open-failed") OPEN_FAILED,
    @SerialName("unsupported-format") UNSUPPORTED_FORMAT,
    @SerialName("network") NETWORK,
    @SerialName("unauthorized") UNAUTHORIZED,
    @SerialName("reader") READER,
}

/**
 * Reader settings; maps 1:1 to reader-core `ReaderOptions` (theme, typography, layout). Defaults
 * match the Nextcloud web reader. Stored by the SettingsRepository as JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReaderSettings(
    /** `auto|light|dark|sepia` */
    @EncodeDefault val theme: String = "auto",
    @EncodeDefault val fontSize: Int = 100,
    @EncodeDefault val fontFamily: String = "",
    @EncodeDefault val lineHeight: Double = 1.6,
    @EncodeDefault val margin: Int = 48,
    /** `paginated|scrolled` */
    @EncodeDefault val flow: String = "paginated",
    @EncodeDefault val maxColumns: Int = 2,
    /** Comics: `single|double` */
    @EncodeDefault val comicSpread: String = "single",
    @EncodeDefault val comicRtl: Boolean = false,
    /** Comics: `fit-page|fit-width` */
    @EncodeDefault val comicZoom: String = "fit-page",
    /** E-ink mode: no animations, high contrast, no transitions. */
    @EncodeDefault val einkMode: Boolean = false,
)
