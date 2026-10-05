package com.somecatcode.ebookreader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import com.somecatcode.ebookreader.data.repo.MissingFields
import com.somecatcode.ebookreader.ui.util.TagNode
import com.somecatcode.ebookreader.ui.util.TermState
import com.somecatcode.ebookreader.ui.util.buildTagTree
import com.somecatcode.ebookreader.ui.util.flattenTree
import com.somecatcode.ebookreader.ui.util.stateOf
import com.somecatcode.ebookreader.ui.util.term
import com.somecatcode.ebookreader.ui.util.termName
import com.somecatcode.ebookreader.ui.util.termType

/** Chip bar above the book list: filter sheet, status, hide finished, offline, active terms, smart shelf actions. */
@Composable
internal fun FilterRow(state: LibraryUiState, actions: LibraryActions) {
    val query = state.query
    val filter = query.filter
    var sheet by rememberSaveable { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    Column {
        state.editingShelf?.let { shelf ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.filter_editing_shelf, shelf.name),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (state.editingShelfChanged) {
                        TextButton(onClick = actions.onUpdateEditingShelf, modifier = Modifier.testTag("update_shelf")) {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                    IconButton(onClick = actions.onStopEditingShelf) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.filter_stop_editing))
                    }
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                val n = filter.include.size + filter.exclude.size
                FilterChip(
                    selected = n > 0,
                    onClick = { sheet = true },
                    leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
                    label = { Text(if (n > 0) "${stringResource(R.string.filter_open)} ($n)" else stringResource(R.string.filter_open)) },
                    modifier = Modifier.testTag("filter_open"),
                )
            }
            item {
                Box {
                    FilterChip(
                        selected = filter.status != null,
                        onClick = { statusMenu = true },
                        label = { Text(filter.status?.let { stringResource(statusTitle(it)) } ?: stringResource(R.string.filter_status)) },
                        modifier = Modifier.testTag("filter_status"),
                    )
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.filter_any)) }, onClick = { statusMenu = false; actions.onStatus(null) })
                        ReadStatus.entries.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(stringResource(statusTitle(status))) },
                                onClick = { statusMenu = false; actions.onStatus(status) },
                                modifier = Modifier.testTag("status_${status.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
            item {
                FilterChip(
                    selected = state.hideFinished && filter.status == null,
                    enabled = filter.status == null,
                    onClick = { actions.onHideFinished(!state.hideFinished) },
                    label = { Text(stringResource(R.string.filter_hide_finished)) },
                    modifier = Modifier.testTag("filter_hide_finished"),
                )
            }
            item {
                FilterChip(
                    selected = filter.onlyOffline,
                    onClick = { actions.onOnlyOffline(!filter.onlyOffline) },
                    label = { Text(stringResource(R.string.filter_offline_only)) },
                    modifier = Modifier.testTag("filter_offline"),
                )
            }
        }
        if (filter.include.isNotEmpty() || filter.exclude.isNotEmpty() || query.hasActiveFilter) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(filter.include.map { it to false } + filter.exclude.map { it to true }, key = { it.first + it.second }) { (t, excluded) ->
                    TermChip(t, excluded, state, onFlip = { actions.onFlipTerm(t) }, onRemove = { actions.onRemoveTerm(t) })
                }
                if (filter.include.size > 1) {
                    item {
                        FilterChip(
                            selected = filter.matchAny,
                            onClick = { actions.onMatchAny(!filter.matchAny) },
                            label = { Text(stringResource(if (filter.matchAny) R.string.filter_match_any else R.string.filter_match_all)) },
                            modifier = Modifier.testTag("filter_match"),
                        )
                    }
                }
                if (query.hasActiveFilter && state.editingShelf == null) {
                    item {
                        TextButton(onClick = { saveDialog = true }, modifier = Modifier.testTag("save_smart")) {
                            Text(stringResource(R.string.filter_save_smart))
                        }
                    }
                }
                if (query.hasActiveFilter) {
                    item { TextButton(onClick = actions.onClearFilters) { Text(stringResource(R.string.filter_clear_all)) } }
                }
            }
        }
    }
    if (sheet) FilterSheet(state, filter, actions, onDismiss = { sheet = false })
    if (saveDialog) {
        NameDialog(
            title = stringResource(R.string.shelf_new_smart),
            initial = "",
            onDismiss = { saveDialog = false },
            onConfirm = { name -> saveDialog = false; actions.onSaveSmartShelf(name) },
        )
    }
}

@Composable
private fun TermChip(t: String, excluded: Boolean, state: LibraryUiState, onFlip: () -> Unit, onRemove: () -> Unit) {
    val label = termLabel(t, excluded, state)
    InputChip(
        selected = true,
        onClick = onFlip,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(if (excluded) Icons.Filled.Remove else Icons.Filled.Add, contentDescription = null, Modifier.size(InputChipDefaults.IconSize))
        },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.filter_remove_term, label),
                modifier = Modifier.size(InputChipDefaults.IconSize).clickable(onClick = onRemove),
            )
        },
        colors = if (excluded) {
            InputChipDefaults.inputChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                selectedTrailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            InputChipDefaults.inputChipColors()
        },
        modifier = Modifier.testTag("term_$t"),
    )
}

/** Readable chip label: format upper case, shelf name, wildcard terms as "X (+ sub)", missing fields as "Without/Has …". */
@Composable
internal fun termLabel(t: String, excluded: Boolean, state: LibraryUiState): String {
    val name = termName(t)
    return when (termType(t)) {
        "format" -> name.uppercase()
        "shelf" -> stringResource(R.string.filter_shelf_term, state.shelves.firstOrNull { it.key.shelfId.toString() == name }?.name ?: name)
        "missing" -> missingLabel(name, has = excluded)
        else -> if (name.endsWith("/*")) stringResource(R.string.filter_with_sub, name.dropLast(2)) else name
    }
}

@Composable
private fun missingLabel(field: String, has: Boolean): String = stringResource(
    when (field) {
        "genre" -> if (has) R.string.filter_has_genre else R.string.filter_missing_genre
        "tag" -> if (has) R.string.filter_has_tag else R.string.filter_missing_tag
        "author" -> if (has) R.string.filter_has_author else R.string.filter_missing_author
        "series" -> if (has) R.string.filter_has_series else R.string.filter_missing_series
        "description" -> if (has) R.string.filter_has_description else R.string.filter_missing_description
        "cover" -> if (has) R.string.filter_has_cover else R.string.filter_missing_cover
        else -> if (has) R.string.filter_has_language else R.string.filter_missing_language
    },
)

private enum class FilterSection(val title: Int) {
    GENRES(R.string.filter_section_genres),
    TAGS(R.string.filter_section_tags),
    AUTHORS(R.string.filter_section_authors),
    SERIES(R.string.filter_section_series),
    FORMATS(R.string.filter_section_formats),
    MISSING(R.string.filter_section_missing),
}

/** Bottom sheet with all facets; every entry cycles off → include → exclude → off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(state: LibraryUiState, filter: LibraryFilter, actions: LibraryActions, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var section by rememberSaveable { mutableStateOf(FilterSection.GENRES) }
    var search by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val facets = state.facets
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("filter_sheet")) {
        Column(Modifier.fillMaxHeight(0.9f)) {
            Text(stringResource(R.string.filter_sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.filter_hint_cycle),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 8.dp) {
                FilterSection.entries.forEach { s ->
                    Tab(
                        selected = section == s,
                        onClick = { section = s; search = "" },
                        text = { Text(stringResource(s.title)) },
                        modifier = Modifier.testTag("filter_section_${s.name.lowercase()}"),
                    )
                }
            }
            if (section != FilterSection.FORMATS && section != FilterSection.MISSING) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.filter_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("filter_search"),
                )
            }
            val rows: List<FacetRow> = when (section) {
                FilterSection.GENRES -> treeRows("genre", facets.genres, expanded, search)
                FilterSection.TAGS -> treeRows("tag", facets.tags, expanded, search)
                FilterSection.AUTHORS -> flatRows("author", facets.authors, search)
                FilterSection.SERIES -> flatRows("series", facets.series, search)
                FilterSection.FORMATS -> facets.formats.map { FacetRow(term("format", it.name), it.name.uppercase(), it.count.toString()) }
                FilterSection.MISSING -> MissingFields
                    .filter { (facets.missing[it] ?: 0) > 0 || filter.stateOf(term("missing", it)) != TermState.OFF }
                    .map { FacetRow(term("missing", it), "", (facets.missing[it] ?: 0).toString(), missingField = it) }
            }
            if (rows.isEmpty()) {
                Text(stringResource(R.string.filter_none_available), Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows, key = { it.term + it.depth }) { row ->
                    val st = filter.stateOf(row.term)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { actions.onCycleTerm(row.term) }
                            .padding(start = 16.dp + (row.depth * 20).dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                            .testTag("facet_${row.term}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StateIcon(st)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            row.missingField?.let { missingLabel(it, has = st == TermState.EXCLUDE) } ?: row.label,
                            Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (st == TermState.EXCLUDE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(row.count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.expandKey != null) {
                            val open = row.expandKey in expanded || search.isNotBlank()
                            IconButton(
                                onClick = { expanded = if (row.expandKey in expanded) expanded - row.expandKey else expanded + row.expandKey },
                                enabled = search.isBlank(),
                            ) {
                                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            }
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                    }
                }
            }
        }
    }
}

private data class FacetRow(
    val term: String,
    val label: String,
    val count: String,
    val depth: Int = 0,
    /** Lower-case path for expand/collapse, null for leaves. */
    val expandKey: String? = null,
    val missingField: String? = null,
)

private fun treeRows(type: String, facets: List<FacetCount>, expanded: Set<String>, search: String): List<FacetRow> =
    flattenTree(buildTagTree(facets), expanded, search).map { n: TagNode ->
        FacetRow(
            term = term(type, n.filterName()),
            label = n.label,
            count = if (n.hasChildren) "≈${n.totalCount}" else n.totalCount.toString(),
            depth = n.depth,
            expandKey = if (n.hasChildren) n.path.lowercase() else null,
        )
    }

private fun flatRows(type: String, facets: List<FacetCount>, search: String): List<FacetRow> =
    facets.filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
        .map { FacetRow(term(type, it.name), it.name, it.count.toString()) }

@Composable
private fun StateIcon(state: TermState) {
    when (state) {
        TermState.OFF -> Spacer(Modifier.size(24.dp))
        TermState.INCLUDE -> Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        TermState.EXCLUDE -> Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    }
}

/** Single text field dialog for shelf names. */
@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 255) name = it },
                label = { Text(stringResource(R.string.shelf_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("shelf_name_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank() && name.trim() != initial) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
