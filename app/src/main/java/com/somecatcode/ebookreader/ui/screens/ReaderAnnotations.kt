package com.somecatcode.ebookreader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.repo.ANNOTATION_MAX_NOTE
import com.somecatcode.ebookreader.data.repo.AnnotationColor
import com.somecatcode.ebookreader.data.repo.AnnotationKind
import com.somecatcode.ebookreader.data.repo.BookAnnotation
import com.somecatcode.ebookreader.ui.theme.LocalEinkMode
import kotlin.math.roundToInt

/*
 * Highlights and notes in the reader: popup of a highlight, note dialog and the list
 * "Highlights & notes" (web: AnnotationPopup.vue, AnnotationNoteDialog.vue, ReaderAnnotations.vue).
 * Nothing animates in e-ink mode: the popup is a plain surface, the list a full screen page there.
 */

/** Callbacks of the annotation UI of the reader. */
data class AnnotationActions(
    val onColor: (String, AnnotationColor) -> Unit = { _, _ -> },
    val onEditNote: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onDismissPopup: () -> Unit = {},
    val onSaveNote: (String) -> Unit = {},
    val onDismissNote: () -> Unit = {},
    val onShowList: () -> Unit = {},
    val onHideList: () -> Unit = {},
    val onJump: (BookAnnotation) -> Unit = {},
)

@Composable
internal fun colorLabel(color: AnnotationColor): String = stringResource(
    when (color) {
        AnnotationColor.YELLOW -> R.string.annotation_color_yellow
        AnnotationColor.GREEN -> R.string.annotation_color_green
        AnnotationColor.BLUE -> R.string.annotation_color_blue
        AnnotationColor.PINK -> R.string.annotation_color_pink
        AnnotationColor.PURPLE -> R.string.annotation_color_purple
    },
)

private val PopupWidth = 320.dp
private val PopupHeight = 56.dp
private val PopupGap = 8.dp

/** Places the popup above the anchor, below it when there is no room, always inside the page. */
internal fun popupOffset(anchor: AnnotationPopup, pageWidth: Dp, pageHeight: Dp): Pair<Dp, Dp> {
    val r = anchor.rect
    val center = ((r.left + r.right) / 2).toFloat()
    val x = (center - PopupWidth.value / 2).coerceIn(PopupGap.value, maxOf(PopupGap.value, pageWidth.value - PopupWidth.value - PopupGap.value))
    val above = r.top.toFloat() - PopupHeight.value - PopupGap.value
    val y = (if (above >= PopupGap.value) above else r.bottom.toFloat() + PopupGap.value)
        .coerceIn(PopupGap.value, maxOf(PopupGap.value, pageHeight.value - PopupHeight.value - PopupGap.value))
    return x.dp to y.dp
}

/** Color choice, note and delete for one highlight, drawn over the page at the highlight. */
@Composable
fun AnnotationPopupOverlay(state: ReaderUiState, actions: AnnotationActions) {
    val popup = state.popup ?: return
    val annotation = state.annotations.firstOrNull { it.uuid == popup.uuid } ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (x, y) = popupOffset(popup, maxWidth, maxHeight)
        Surface(
            modifier = Modifier
                .offset(x.roundToDp(), y.roundToDp())
                .width(PopupWidth)
                .height(PopupHeight)
                .testTag("annotation_popup"),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = if (LocalEinkMode.current) 0.dp else 6.dp,
            border = if (LocalEinkMode.current) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AnnotationColor.entries.forEach { c ->
                    ColorDot(c, selected = c == (annotation.color ?: AnnotationColor.YELLOW)) { actions.onColor(annotation.uuid, c) }
                }
                Spacer(Modifier.weight(1f))
                val noteLabel = stringResource(if (annotation.note.isNullOrBlank()) R.string.annotation_add_note else R.string.annotation_edit_note)
                IconButton(onClick = { actions.onEditNote(annotation.uuid) }, modifier = Modifier.testTag("annotation_popup_note")) {
                    Icon(Icons.Filled.EditNote, contentDescription = noteLabel)
                }
                IconButton(onClick = { actions.onDelete(annotation.uuid) }, modifier = Modifier.testTag("annotation_popup_delete")) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.annotation_delete))
                }
            }
        }
    }
}

private fun Dp.roundToDp(): Dp = value.roundToInt().dp

@Composable
private fun ColorDot(color: AnnotationColor, selected: Boolean, onClick: () -> Unit) {
    val label = colorLabel(color)
    val description = if (selected) stringResource(R.string.annotation_color_selected, label) else label
    Box(
        Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .testTag("annotation_color_${color.wire}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(Color(color.argb), CircleShape)
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
        }
    }
}

/** Note of a new selection or of an existing annotation; the quote is shown above the text field. */
@Composable
fun NoteEditorDialog(editor: NoteEditor, actions: AnnotationActions) {
    var text by rememberSaveable(editor.uuid, editor.quote) { mutableStateOf(editor.initial) }
    AlertDialog(
        onDismissRequest = actions.onDismissNote,
        title = { Text(stringResource(if (editor.uuid == null) R.string.annotation_note_title_new else R.string.annotation_edit_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                editor.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                    Text(
                        "„$quote“",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ANNOTATION_MAX_NOTE) },
                    label = { Text(stringResource(R.string.annotation_note_label)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("annotation_note_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onSaveNote(text) },
                // a new note needs text; clearing the note of an existing highlight is allowed
                enabled = editor.uuid != null || text.isNotBlank(),
                modifier = Modifier.testTag("annotation_note_save"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = actions.onDismissNote) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * "Highlights & notes" in e-ink mode: a plain full screen page without animation. Placed inside the
 * reader's box (on top of the page); [AnnotationsSheet] is the animated variant.
 */
@Composable
fun AnnotationsPage(state: ReaderUiState, actions: AnnotationActions) {
    if (!state.showAnnotations || !LocalEinkMode.current) return
    run {
        Surface(Modifier.fillMaxSize().testTag("annotations_page"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.onHideList) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_back)) }
                    Text(stringResource(R.string.reader_annotations), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
                AnnotationList(state, actions, Modifier.weight(1f))
            }
        }
    }
}

/** "Highlights & notes" as a bottom sheet (not in e-ink mode, see [AnnotationsPage]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsSheet(state: ReaderUiState, actions: AnnotationActions) {
    if (!state.showAnnotations || LocalEinkMode.current) return
    run {
        ModalBottomSheet(onDismissRequest = actions.onHideList, modifier = Modifier.testTag("annotations_sheet")) {
            Text(
                stringResource(R.string.reader_annotations),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            AnnotationList(state, actions, Modifier)
        }
    }
}

private sealed interface ListRow {
    data class Header(val kind: AnnotationKind) : ListRow
    data class Item(val annotation: BookAnnotation) : ListRow
}

@Composable
private fun AnnotationList(state: ReaderUiState, actions: AnnotationActions, modifier: Modifier) {
    val rows = remember(state.annotations) {
        listOf(AnnotationKind.HIGHLIGHT, AnnotationKind.NOTE, AnnotationKind.BOOKMARK).flatMap { kind ->
            val items = state.annotations.filter { it.kind == kind }
            if (items.isEmpty()) emptyList() else listOf(ListRow.Header(kind)) + items.map { ListRow.Item(it) }
        }
    }
    if (rows.isEmpty()) {
        Text(
            stringResource(if (state.supportsAnnotations) R.string.annotations_empty else R.string.annotations_empty_fixed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("annotations_empty"),
        )
        return
    }
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding()) {
        items(rows, key = { row -> if (row is ListRow.Item) row.annotation.uuid else "h-${(row as ListRow.Header).kind}" }) { row ->
            when (row) {
                is ListRow.Header -> Text(
                    stringResource(
                        when (row.kind) {
                            AnnotationKind.HIGHLIGHT -> R.string.annotations_group_highlights
                            AnnotationKind.NOTE -> R.string.annotations_group_notes
                            AnnotationKind.BOOKMARK -> R.string.annotations_group_bookmarks
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                is ListRow.Item -> AnnotationRow(row.annotation, actions)
            }
        }
    }
}

/** Chapter title and position in the book (web `positionLabel`). */
internal fun positionLabel(a: BookAnnotation): String = listOfNotNull(
    a.locator.title?.takeIf { it.isNotBlank() },
    a.locator.locations?.totalProgression?.let { "${(it * 100).roundToInt()} %" },
).joinToString(", ")

@Composable
private fun AnnotationRow(a: BookAnnotation, actions: AnnotationActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.onJump(a) }
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .testTag("annotation_${a.uuid}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.kind != AnnotationKind.BOOKMARK) {
            Box(Modifier.width(4.dp).height(40.dp).background(Color(((a.color ?: AnnotationColor.YELLOW).argb)), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val text = a.text?.takeIf { it.isNotBlank() }
            if (a.kind == AnnotationKind.BOOKMARK) {
                Text(text ?: stringResource(R.string.annotation_bookmark), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (text != null) {
                Text("„$text“", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            a.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(positionLabel(a), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (a.pending) {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = stringResource(R.string.annotation_pending),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp),
                    )
                }
            }
        }
        if (a.kind != AnnotationKind.BOOKMARK) {
            val noteLabel = stringResource(if (a.note.isNullOrBlank()) R.string.annotation_add_note else R.string.annotation_edit_note)
            IconButton(onClick = { actions.onEditNote(a.uuid) }, modifier = Modifier.testTag("annotation_edit_${a.uuid}")) {
                Icon(Icons.Filled.EditNote, contentDescription = noteLabel)
            }
        }
        IconButton(onClick = { actions.onDelete(a.uuid) }, modifier = Modifier.testTag("annotation_delete_${a.uuid}")) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.annotation_delete))
        }
    }
}
