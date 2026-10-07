package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.Locator
import kotlinx.coroutines.flow.Flow

/*
 * Highlights, notes and bookmarks (server docs/CONTRACTS-v4.md section 4). Offline first like the
 * reading progress: every change is written to Room at once (`dirty`), uploaded by [AnnotationRepository.pushDirty]
 * (push worker, PUSH_LOCAL phase of every sync) and merged from the delta sync by `uuid`.
 * Conflict rule (same as the server): the larger `clientUpdatedAt` wins.
 */

/** Wire values of the server (`type`). */
enum class AnnotationType(val wire: String) {
    HIGHLIGHT("highlight"), NOTE("note"), BOOKMARK("bookmark");

    companion object {
        fun fromWire(value: String?): AnnotationType = entries.firstOrNull { it.wire == value } ?: HIGHLIGHT
    }
}

/** Highlight colors of the web reader (reader-core `ANNOTATION_COLORS`), same order. */
enum class AnnotationColor(val wire: String, val argb: Long) {
    YELLOW("yellow", 0xFFFFD43B),
    GREEN("green", 0xFF51CF66),
    BLUE("blue", 0xFF4DABF7),
    PINK("pink", 0xFFF06595),
    PURPLE("purple", 0xFF9775FA);

    companion object {
        fun fromWire(value: String?): AnnotationColor? = entries.firstOrNull { it.wire == value }
    }
}

/** How the UI groups an annotation (web `kindOf`): a highlight with a note is a note. */
enum class AnnotationKind { HIGHLIGHT, NOTE, BOOKMARK }

data class BookAnnotation(
    val key: BookKey,
    val uuid: String,
    val type: AnnotationType,
    val locator: Locator,
    val text: String?,
    val note: String?,
    val color: AnnotationColor?,
    val createdAt: Long,
    val clientUpdatedAt: Long,
    /** Local change not yet on the server. */
    val pending: Boolean,
) {
    val kind: AnnotationKind
        get() = when {
            type == AnnotationType.BOOKMARK -> AnnotationKind.BOOKMARK
            !note.isNullOrBlank() -> AnnotationKind.NOTE
            else -> AnnotationKind.HIGHLIGHT
        }

    /** Range CFI the reader draws (highlights and notes of reflowable books only). */
    val cfi: String? get() = if (type == AnnotationType.BOOKMARK) null else locator.locations?.cfi?.takeIf { it.isNotBlank() }
}

interface AnnotationRepository {
    /** Live annotations of a book in reading order (CFI, then position in the book, then creation time). */
    fun annotations(key: BookKey): Flow<List<BookAnnotation>>

    /**
     * New highlight ([note] null/blank) or note of a text selection. Returns the uuid. Text and note are cut
     * to the server limits. Never blocks on the network.
     */
    suspend fun create(key: BookKey, locator: Locator, text: String?, color: AnnotationColor, note: String? = null): String

    /** Changes the note; null or blank removes it (the highlight stays). */
    suspend fun setNote(key: BookKey, uuid: String, note: String?)

    suspend fun setColor(key: BookKey, uuid: String, color: AnnotationColor)

    /** Deletes locally at once; the deletion is uploaded as a tombstone. */
    suspend fun delete(key: BookKey, uuid: String)

    /**
     * Reloads the annotations of one book from the server (book opened online): newer server rows replace
     * clean local ones, clean local rows the server no longer lists are removed. Failures are ignored.
     */
    suspend fun refresh(key: BookKey)

    /** Uploads all dirty rows of the account (upsert by uuid, tombstones as DELETE). Network errors stop silently. */
    suspend fun pushDirty(accountId: String)
}

/** Server limits (characters). */
const val ANNOTATION_MAX_TEXT = 2000
const val ANNOTATION_MAX_NOTE = 10000
