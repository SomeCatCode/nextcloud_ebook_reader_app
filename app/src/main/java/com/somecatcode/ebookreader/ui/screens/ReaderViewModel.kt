package com.somecatcode.ebookreader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.ProgressConflict
import com.somecatcode.ebookreader.data.repo.ProgressRepository
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.reader.BookRef
import com.somecatcode.ebookreader.reader.BookSource
import com.somecatcode.ebookreader.reader.ErrorCode
import com.somecatcode.ebookreader.reader.HostToReader
import com.somecatcode.ebookreader.reader.ReaderSettings
import com.somecatcode.ebookreader.reader.ReaderToHost
import com.somecatcode.ebookreader.reader.TocItem
import com.somecatcode.ebookreader.ui.util.readerSettings
import com.somecatcode.ebookreader.ui.util.withReaderSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ReaderPhase { LOADING, CONFLICT, OPENING, READING, ERROR }

enum class ReaderFailure { NOT_FOUND, NOT_DOWNLOADABLE, NETWORK, UNAUTHORIZED, UNSUPPORTED, OPEN_FAILED }

data class ReaderUiState(
    val phase: ReaderPhase = ReaderPhase.LOADING,
    val title: String = "",
    val conflict: ProgressConflict? = null,
    /** Message the screen forwards to the host once; replaced on every (re)open. */
    val openMessage: HostToReader.Open? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val toc: List<TocItem> = emptyList(),
    val percentage: Double = 0.0,
    val label: String? = null,
    val barsVisible: Boolean = false,
    val showToc: Boolean = false,
    val showSettings: Boolean = false,
    val externalLink: String? = null,
    val keepScreenOn: Boolean = true,
    val failure: ReaderFailure? = null,
    /** Comic or fixed-layout book: the settings offer fit page / fit width instead of text options. */
    val fixedLayout: Boolean = false,
)

/** How the book is delivered to the page: offline file, or (online) entry-wise/page-wise where possible. */
internal fun buildBookSource(format: String, fileName: String, offline: Boolean): BookSource = when {
    offline -> BookSource.File(fileName = fileName)
    format.lowercase() in setOf("epub", "fbz") -> BookSource.RemoteZip(name = fileName)
    format.lowercase() in setOf("cbz", "cbr", "cb7", "cbt") -> BookSource.RemoteComic(name = fileName)
    else -> BookSource.File(fileName = fileName)
}

internal fun isSafeExternalUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return scheme in setOf("http", "https", "mailto") && url.length > scheme.length + 1
}

class ReaderViewModel(
    val key: BookKey,
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val downloads: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    keepScreenOn: Boolean = true,
    private val onKeepScreenOnChanged: (Boolean) -> Unit = {},
    private val remoteCheckTimeoutMs: Long = 6_000,
    private val saveDebounceMs: Long = 1_000,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState(keepScreenOn = keepScreenOn))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var book: LibraryBook? = null
    private var offline = false
    private var pendingSave: Pair<Locator, Double>? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val loaded = library.book(key).first()
        if (loaded == null) return fail(ReaderFailure.NOT_FOUND)
        if (!loaded.downloadable) return fail(ReaderFailure.NOT_DOWNLOADABLE)
        book = loaded
        val settings = settingsRepository.settings.first().readerSettings()
        _state.update { it.copy(title = loaded.title, settings = settings) }
        offline = downloads.localFile(key) != null
        val conflict = withTimeoutOrNull(remoteCheckTimeoutMs) { runCatching { progress.checkRemote(key) }.getOrNull() }
        if (conflict != null) {
            _state.update { it.copy(phase = ReaderPhase.CONFLICT, conflict = conflict) }
        } else {
            openAt(progress.progress(key).first()?.locator)
        }
    }

    private fun fail(reason: ReaderFailure) {
        _state.update { it.copy(phase = ReaderPhase.ERROR, failure = reason) }
    }

    private fun openAt(locator: Locator?) {
        val b = book ?: return
        val message = HostToReader.Open(
            book = BookRef(key.accountId, key.fileId, b.format, b.title),
            source = buildBookSource(b.format, b.path.substringAfterLast('/').ifBlank { "${b.title}.${b.format}" }, offline),
            initialLocator = locator,
            settings = _state.value.settings,
        )
        _state.update { it.copy(phase = ReaderPhase.OPENING, conflict = null, openMessage = message) }
    }

    /** Conflict dialog: jump to the position of the other device or keep the local one. */
    fun resolveConflict(acceptRemote: Boolean) {
        val conflict = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null, phase = ReaderPhase.OPENING) }
        viewModelScope.launch {
            if (acceptRemote) {
                progress.acceptRemote(conflict)
                openAt(conflict.remoteLocator)
            } else {
                progress.keepLocal(conflict)
                openAt(conflict.localLocator)
            }
        }
    }

    /** Events from the page (collected by the screen). */
    fun onEvent(event: ReaderToHost) {
        when (event) {
            is ReaderToHost.Ready -> Unit
            is ReaderToHost.Opened -> _state.update {
                it.copy(
                    phase = ReaderPhase.READING,
                    title = event.info.title ?: it.title,
                    fixedLayout = event.info.isComic || event.info.fixedLayout,
                )
            }
            is ReaderToHost.Relocate -> {
                pendingSave = event.locator to event.percentage
                _state.update { it.copy(percentage = event.percentage, label = event.label) }
                saveJob?.cancel()
                saveJob = viewModelScope.launch {
                    delay(saveDebounceMs)
                    flushProgress()
                }
            }
            is ReaderToHost.Toc -> _state.update { it.copy(toc = event.items) }
            is ReaderToHost.ExternalLink -> if (isSafeExternalUrl(event.url)) _state.update { it.copy(externalLink = event.url) }
            is ReaderToHost.Tap -> if (event.zone == "center") toggleBars()
            is ReaderToHost.Error -> fail(
                when (event.code) {
                    ErrorCode.NETWORK -> ReaderFailure.NETWORK
                    ErrorCode.UNAUTHORIZED -> ReaderFailure.UNAUTHORIZED
                    ErrorCode.UNSUPPORTED_FORMAT -> ReaderFailure.UNSUPPORTED
                    ErrorCode.OPEN_FAILED, ErrorCode.READER -> ReaderFailure.OPEN_FAILED
                },
            )
        }
    }

    /** Writes the last known position now (pause/leave); never blocked by the network. */
    fun flushProgress() {
        val (locator, percentage) = pendingSave ?: return
        pendingSave = null
        saveJob?.cancel()
        viewModelScope.launch(NonCancellable) { progress.saveLocal(key, locator, percentage) }
    }

    fun toggleBars() = _state.update { it.copy(barsVisible = !it.barsVisible) }

    fun showToc(show: Boolean) = _state.update { it.copy(showToc = show, barsVisible = if (show) it.barsVisible else false) }

    fun showSettings(show: Boolean) = _state.update { it.copy(showSettings = show) }

    fun dismissExternalLink() = _state.update { it.copy(externalLink = null) }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val updated = transform(_state.value.settings)
        _state.update { it.copy(settings = updated) }
        viewModelScope.launch {
            // einkMode comes from the app setting, not from the stored reader defaults.
            settingsRepository.update { s -> s.withReaderSettings(updated.copy(einkMode = false)) }
        }
    }

    fun setKeepScreenOn(keep: Boolean) {
        _state.update { it.copy(keepScreenOn = keep) }
        onKeepScreenOnChanged(keep)
    }
}
