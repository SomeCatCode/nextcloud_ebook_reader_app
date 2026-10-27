package com.somecatcode.ebookreader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.ReadNextSuggestions
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.LongstripBadge

/**
 * What to read after a book: a button for the next volume of the series and a row of similar unread titles.
 * Used by the "Fertig gelesen" sheet of the reader and by the details of a finished book.
 */
@Composable
fun ReadNextBlock(
    suggestions: ReadNextSuggestions,
    onReadNext: (BookKey) -> Unit,
    onOpenBook: (BookKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        suggestions.next?.let { next ->
            Button(onClick = { onReadNext(next.key) }, modifier = Modifier.fillMaxWidth().testTag("read_next")) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    stringResource(R.string.read_next_continue, next.title),
                    Modifier.padding(start = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (suggestions.similar.isNotEmpty()) {
            Text(stringResource(R.string.read_next_similar), style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("read_next_similar")) {
                items(suggestions.similar, key = { "${it.key.accountId}/${it.key.fileId}" }) { book ->
                    Column(Modifier.width(96.dp).clickable { onOpenBook(book.key) }.testTag("similar_${book.key.fileId}")) {
                        androidx.compose.foundation.layout.Box {
                            BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
                            LongstripBadge(book.longstrip, Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(4.dp))
                        }
                        Text(
                            book.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
