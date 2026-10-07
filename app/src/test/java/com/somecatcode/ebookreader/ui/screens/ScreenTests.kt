package com.somecatcode.ebookreader.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.Locations
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.repo.AnnotationColor
import com.somecatcode.ebookreader.data.repo.AnnotationType
import com.somecatcode.ebookreader.data.repo.BookAnnotation
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.reader.SelectionRect
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.data.repo.EditFailure
import com.somecatcode.ebookreader.data.repo.OfflineItem
import com.somecatcode.ebookreader.data.repo.OfflineState
import com.somecatcode.ebookreader.data.repo.ProgressConflict
import com.somecatcode.ebookreader.ui.FakeAccountStore
import com.somecatcode.ebookreader.ui.FakeContainer
import com.somecatcode.ebookreader.ui.FakeDownloadRepository
import com.somecatcode.ebookreader.ui.FakeLibraryRepository
import com.somecatcode.ebookreader.ui.FakeProgressRepository
import com.somecatcode.ebookreader.ui.account
import com.somecatcode.ebookreader.ui.book
import com.somecatcode.ebookreader.ui.theme.EbookReaderTheme
import com.somecatcode.ebookreader.ui.util.LocalAppContainer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Robolectric Compose tests of the main screens against fake repositories. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScreenTests {

    @get:Rule
    val compose = createComposeRule()

    private fun container(
        accounts: FakeAccountStore = FakeAccountStore(listOf(account())),
        library: FakeLibraryRepository = FakeLibraryRepository(),
        progress: FakeProgressRepository = FakeProgressRepository(),
        downloads: FakeDownloadRepository = FakeDownloadRepository(),
    ) = FakeContainer(
        ApplicationProvider.getApplicationContext(),
        accountStore = accounts, libraryRepository = library, progressRepository = progress, downloadRepository = downloads,
    )

    private fun show(container: FakeContainer, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { EbookReaderTheme(dynamicColor = false) { content() } }
        }
    }

    // ---- accounts ---------------------------------------------------------------------------------

    @Test
    fun addAccount_validatesAddressThenShowsWaitingStateWithCancel() {
        val c = container(accounts = FakeAccountStore())
        show(c) { AccountsScreen(onBack = {}) }
        compose.onNodeWithText("No accounts yet").assertIsDisplayed()

        compose.onNodeWithTag("add_account").performClick()
        compose.onNodeWithTag("server_input").performTextInput("http://plain.example.org")
        compose.onNodeWithTag("sign_in").performClick()
        compose.onNodeWithText("Enter a valid HTTPS address, for example cloud.example.org.").assertIsDisplayed()
        assertTrue(c.loginFlowClient.started.isEmpty())

        compose.onNodeWithTag("server_input").performTextReplacement("cloud.example.org")
        compose.onNodeWithTag("sign_in").performClick()
        compose.waitUntil(5_000) { c.loginFlowClient.started.isNotEmpty() }
        assertEquals("https://cloud.example.org", c.loginFlowClient.started.single())
        compose.onNodeWithText("Waiting for the browser…").assertIsDisplayed()
        compose.onNodeWithTag("cancel_login").performClick()
        compose.onNodeWithTag("server_input").assertIsDisplayed()
    }

    @Test
    fun accountList_showsStatusAndRemoveAsksForConfirmation() {
        val c = container(accounts = FakeAccountStore(listOf(account("acc1", "Alice")), missingCredentials = setOf("acc1")))
        show(c) { AccountsScreen(onBack = {}) }
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("Sign-in expired. Please sign in again.").assertIsDisplayed()
        compose.onNodeWithText("Sign in again").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove account?").assertIsDisplayed()
        compose.onNodeWithText("Remove account").performClick()
        compose.waitUntil(5_000) { c.accountStore.removed.isNotEmpty() }
        assertEquals(listOf("acc1"), c.accountStore.removed)
    }

    // ---- library ----------------------------------------------------------------------------------

    private fun libraryContainer() = container(
        library = FakeLibraryRepository(
            listOf(
                book(1, "Dune"),
                book(2, "Emma", offline = OfflineState(DownloadState.DONE)),
                book(3, "Hobbit", status = ReadStatus.READING),
            ),
        ),
    )

    @Test
    fun library_withoutAccountsShowsWelcome() {
        show(container(accounts = FakeAccountStore())) { LibraryScreen({}, {}, {}, { _, _ -> }) }
        compose.onNodeWithText("Welcome to E-Book Reader").assertIsDisplayed()
    }

    @Test
    fun library_filterStateChangesVisibleBooks() {
        show(libraryContainer()) { LibraryScreen({}, {}, {}, { _, _ -> }) }
        compose.onNodeWithText("Dune").assertIsDisplayed()
        compose.onNodeWithText("Emma").assertIsDisplayed()

        compose.onNodeWithTag("filter_offline").performClick()
        compose.onNodeWithText("Emma").assertIsDisplayed()
        compose.onNodeWithText("Dune").assertDoesNotExist()
        compose.onNodeWithTag("filter_offline").performClick()

        compose.onNodeWithTag("filter_status").performClick()
        compose.onNodeWithTag("status_reading").performClick()
        compose.onNodeWithText("Hobbit").assertIsDisplayed()
        compose.onNodeWithText("Dune").assertDoesNotExist()
        compose.onNodeWithText("Clear filters").performClick()
        compose.onNodeWithText("Dune").assertIsDisplayed()
    }

    @Test
    fun library_searchAndLayoutToggle() {
        val c = libraryContainer()
        show(c) { LibraryScreen({}, {}, {}, { _, _ -> }) }
        compose.onNodeWithTag("search_toggle").performClick()
        compose.onNodeWithTag("search_field").performTextInput("emm")
        compose.onNodeWithText("Emma").assertIsDisplayed()
        compose.onNodeWithText("Dune").assertDoesNotExist()
        compose.onNodeWithTag("search_toggle").performClick()
        compose.onNodeWithText("Dune").assertIsDisplayed()

        compose.onNodeWithTag("layout_toggle").performClick()
        compose.waitUntil(5_000) { c.settingsRepository.state.value.libraryLayout == "list" }
        compose.onNodeWithTag("book_list").assertIsDisplayed()
    }

    @Test
    fun library_clickOpensBook() {
        var opened: Pair<String, Long>? = null
        show(libraryContainer()) { LibraryScreen({}, {}, {}, { a, f -> opened = a to f }) }
        compose.onNodeWithTag("book_2").performClick()
        assertEquals("acc1" to 2L, opened)
    }

    // ---- book detail / edit -----------------------------------------------------------------------

    @Test
    fun bookDetail_editFormSendsPatchRatingAndStatus() {
        val library = FakeLibraryRepository(listOf(book(1, "Dune", series = "Dune", seriesIndex = 1.0, genres = listOf("Sci-Fi"))))
        val c = container(library = library)
        show(c) { BookDetailScreen("acc1", 1, onBack = {}, onRead = {}) }
        compose.onNodeWithTag("book_title").assertIsDisplayed()

        compose.onNodeWithTag("edit_book").performClick()
        compose.onNodeWithTag("edit_title").performTextReplacement("Dune Messiah")
        compose.onNodeWithTag("edit_series_index").performTextReplacement("2")
        compose.onNodeWithTag("edit_save").performClick()
        compose.waitUntil(5_000) { c.editRepository.metadataEdits.isNotEmpty() }
        val fields = c.editRepository.metadataEdits.single().second.fields
        assertEquals(JsonPrimitive("Dune Messiah"), fields["title"])
        assertEquals(JsonPrimitive(2.0), fields["seriesIndex"])
        assertEquals(setOf("title", "seriesIndex"), fields.keys)

        compose.onNodeWithContentDescription("4 stars").performScrollTo().performClick()
        compose.onNodeWithTag("read_status_finished").performScrollTo().performClick()
        compose.waitUntil(5_000) { c.editRepository.appDataEdits.size == 2 }
        assertEquals(AppDataPatch(setRating = true, rating = 4), c.editRepository.appDataEdits[0].second)
        assertEquals(AppDataPatch(readStatus = ReadStatus.FINISHED), c.editRepository.appDataEdits[1].second)
    }

    @Test
    fun bookDetail_emptyTitleIsRejectedAndFailuresShowSnackbar() {
        val c = container(library = FakeLibraryRepository(listOf(book(1, "Dune"))))
        show(c) { BookDetailScreen("acc1", 1, onBack = {}, onRead = {}) }
        compose.onNodeWithTag("edit_book").performClick()
        compose.onNodeWithTag("edit_title").performTextReplacement("")
        compose.onNodeWithTag("edit_save").performClick()
        compose.onNodeWithText("The title must not be empty.").assertIsDisplayed()
        assertTrue(c.editRepository.metadataEdits.isEmpty())
        compose.onNodeWithText("Cancel").performClick()

        c.editRepository.failureFlow.tryEmit(EditFailure(BookKey("acc1", 1), "boom"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("The server rejected a change: boom") > 0 }
    }

    @Test
    fun bookDetail_pendingEditAndOfflineToggle() {
        val library = FakeLibraryRepository(listOf(book(1, "Dune", pending = true)))
        val c = container(library = library)
        // The first download asks for the notification permission (Android 13+); grant it up front.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        show(c) { BookDetailScreen("acc1", 1, onBack = {}, onRead = {}) }
        compose.onNodeWithTag("pending_edit").assertIsDisplayed()
        compose.onNodeWithTag("offline_switch").performScrollTo().performClick()
        compose.waitUntil(5_000) { c.downloadRepository.made.isNotEmpty() || c.downloadRepository.removedTargets.isNotEmpty() }
    }

    // ---- reader -----------------------------------------------------------------------------------

    @Test
    fun reader_conflictDialogOffersJumpAndKeep() {
        val key = BookKey("acc1", 1)
        val conflict = ProgressConflict(
            key, Locator("a", locations = Locations(totalProgression = 0.1)), 0.1,
            Locator("b", locations = Locations(totalProgression = 0.5)), 0.5, "Pixel 9", 1000,
        )
        val progress = FakeProgressRepository(conflict = conflict)
        val c = container(library = FakeLibraryRepository(listOf(book(1, "Dune"))), progress = progress)
        show(c) { ReaderScreen("acc1", 1, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Newer position from Pixel 9") > 0 }
        compose.onNodeWithText("Jump there (50 %) or stay at your position on this device (10 %)?").assertIsDisplayed()
        compose.onNodeWithTag("conflict_jump").performClick()
        compose.waitUntil(5_000) { progress.accepted != null }
        assertNotNull(progress.accepted)
    }

    @Test
    fun reader_conflictKeepLocal() {
        val key = BookKey("acc1", 1)
        val conflict = ProgressConflict(key, Locator("a"), 0.1, Locator("b"), 0.5, null, 1000)
        val progress = FakeProgressRepository(conflict = conflict)
        val c = container(library = FakeLibraryRepository(listOf(book(1, "Dune"))), progress = progress)
        show(c) { ReaderScreen("acc1", 1, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Newer position from another device") > 0 }
        compose.onNodeWithTag("conflict_keep").performClick()
        compose.waitUntil(5_000) { progress.kept != null }
    }

    // ---- reader annotations -------------------------------------------------------------------

    private fun annotation(uuid: String, text: String?, note: String?, type: AnnotationType = AnnotationType.HIGHLIGHT, total: Double = 0.25) = BookAnnotation(
        BookKey("acc1", 1), uuid, type, Locator("c1.xhtml", title = "Chapter 1", locations = Locations(cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)", totalProgression = total)),
        text, note, AnnotationColor.GREEN, 1, 1, pending = false,
    )

    private fun showReader(state: ReaderUiState, actions: AnnotationActions, eink: Boolean = false) {
        compose.setContent {
            EbookReaderTheme(dynamicColor = false, einkMode = eink) {
                ReaderContent(
                    state = state, surface = {}, onBack = {}, onJumpRemote = {}, onKeepLocal = {}, onShowToc = {}, onHideToc = {},
                    onTocClick = {}, onShowSettings = {}, onHideSettings = {}, onSettings = {}, onKeepScreenOn = {}, onConfirmLink = {},
                    onDismissLink = {}, annotationActions = actions,
                )
            }
        }
    }

    @Test
    fun reader_annotationListGroupsJumpsAndDeletes() {
        val jumped = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val state = ReaderUiState(
            phase = ReaderPhase.READING, supportsAnnotations = true, showAnnotations = true,
            annotations = listOf(
                annotation("h1", "Hello world", null),
                annotation("n1", "Quoted", "My thought", type = AnnotationType.NOTE),
                annotation("b1", "Page 3", null, type = AnnotationType.BOOKMARK),
            ),
        )
        showReader(state, AnnotationActions(onJump = { jumped += it.uuid }, onDelete = { deleted += it }), eink = true)
        compose.onNodeWithTag("annotations_page").assertIsDisplayed() // e-ink: plain page, no sheet animation
        compose.onNodeWithText("Highlights").assertIsDisplayed()
        compose.onNodeWithText("Notes").assertIsDisplayed()
        compose.onNodeWithText("Bookmarks").assertIsDisplayed()
        compose.onNodeWithText("My thought").assertIsDisplayed()
        compose.onAllNodesWithText("Chapter 1, 25 %").fetchSemanticsNodes().let { assertEquals(3, it.size) }
        compose.onNodeWithTag("annotation_h1").performClick()
        compose.onNodeWithTag("annotation_delete_n1").performClick()
        assertEquals(listOf("h1"), jumped)
        assertEquals(listOf("n1"), deleted)
    }

    @Test
    fun reader_emptyAnnotationListExplainsHowToStart() {
        showReader(ReaderUiState(phase = ReaderPhase.READING, supportsAnnotations = true, showAnnotations = true), AnnotationActions(), eink = true)
        compose.onNodeWithTag("annotations_empty").assertIsDisplayed()
    }

    @Test
    fun reader_highlightPopupChangesColorAndOpensTheNoteDialog() {
        val colors = mutableListOf<AnnotationColor>()
        val saved = mutableListOf<String>()
        val a = annotation("h1", "Hello world", null)
        val state = ReaderUiState(
            phase = ReaderPhase.READING, supportsAnnotations = true, annotations = listOf(a),
            popup = AnnotationPopup("h1", SelectionRect(20.0, 300.0, 200.0, 320.0)),
            noteEditor = NoteEditor("h1", "Hello world", ""),
        )
        showReader(state, AnnotationActions(onColor = { _, c -> colors += c }, onSaveNote = { saved += it }))
        compose.onNodeWithTag("annotation_popup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Green (selected)").assertExists()
        compose.onNodeWithTag("annotation_color_purple").performClick()
        assertEquals(listOf(AnnotationColor.PURPLE), colors)
        compose.onNodeWithTag("annotation_note_field").performTextInput("A note")
        compose.onNodeWithTag("annotation_note_save").performClick()
        assertEquals(listOf("A note"), saved)
    }

    @Test
    fun popupStaysInsideThePage() {
        val (x, y) = popupOffset(AnnotationPopup("a", SelectionRect(0.0, 10.0, 20.0, 30.0)), 360.dp, 640.dp)
        assertEquals(8f, x.value, 0.01f) // clamped to the left edge
        assertEquals(38f, y.value, 0.01f) // no room above: below the selection
        val (x2, y2) = popupOffset(AnnotationPopup("a", SelectionRect(300.0, 400.0, 350.0, 420.0)), 360.dp, 640.dp)
        assertEquals(32f, x2.value, 0.01f) // 360 - 320 - 8
        assertEquals(336f, y2.value, 0.01f) // above: 400 - 56 - 8
    }

    // ---- downloads / settings ---------------------------------------------------------------------

    @Test
    fun downloads_listsItemsAndClearsAfterConfirmation() {
        val downloads = FakeDownloadRepository()
        downloads.itemsState.value = listOf(
            OfflineItem(BookKey("acc1", 1), "Dune", DownloadState.DONE, 2_000_000, 2_000_000, PinnedBy.BOOK, null),
            OfflineItem(BookKey("acc1", 2), "Emma", DownloadState.RUNNING, 500_000, 1_000_000, PinnedBy.SHELF, null),
        )
        downloads.used.value = 2_000_000
        val c = container(downloads = downloads)
        show(c) { DownloadsScreen(onBack = {}) }
        compose.onNodeWithText("Dune").assertIsDisplayed()
        compose.onNodeWithText("Emma").assertIsDisplayed()
        compose.onNodeWithTag("storage_used").assertIsDisplayed()
        compose.onNodeWithTag("clear_all").performClick()
        compose.onNodeWithText("Remove all downloads?").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { downloads.clearedAll }
    }

    @Test
    fun settings_updatesThemeAndEinkMode() {
        val c = container()
        show(c) { SettingsScreen(onBack = {}) }
        compose.onNodeWithTag("theme_dark").performClick()
        compose.onNodeWithTag("eink_mode").performClick()
        compose.waitUntil(5_000) { c.settingsRepository.state.value.einkMode }
        assertEquals("dark", c.settingsRepository.state.value.themeMode)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodesWithText(text).fetchSemanticsNodes().size
