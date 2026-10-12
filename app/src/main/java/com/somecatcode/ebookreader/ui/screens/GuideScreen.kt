package com.somecatcode.ebookreader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.ui.theme.LocalEinkMode
import kotlinx.coroutines.launch

private class GuidePage(val icon: ImageVector, val title: Int, val body: Int)

private val pages = listOf(
    GuidePage(Icons.Filled.AutoStories, R.string.guide_welcome_title, R.string.guide_welcome_body),
    GuidePage(Icons.Filled.CreateNewFolder, R.string.guide_library_title, R.string.guide_library_body),
    GuidePage(Icons.AutoMirrored.Filled.LibraryBooks, R.string.guide_browse_title, R.string.guide_browse_body),
    GuidePage(Icons.Filled.TouchApp, R.string.guide_read_title, R.string.guide_read_body),
    GuidePage(Icons.Filled.SwapHoriz, R.string.guide_move_title, R.string.guide_move_body),
)

/**
 * Short introduction shown at the first start (and from the settings): what the app does, how to add a
 * library (folder or Nextcloud), how to find books, read and move them between accounts.
 *
 * @param hasLibrary at least one account exists; the last page then just closes instead of offering to add one
 * @param onDone [addLibrary] = the user wants to add a folder or account now
 */
@Composable
fun GuideScreen(hasLibrary: Boolean, onDone: (addLibrary: Boolean) -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val eink = LocalEinkMode.current
    val last = pager.currentPage == pages.lastIndex
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.scrollToPage(pager.currentPage - 1) }
    }
    Surface(Modifier.fillMaxSize().testTag("guide"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = { onDone(false) }, modifier = Modifier.testTag("guide_skip")) { Text(stringResource(R.string.guide_skip)) }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
                val page = pages[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
                    }
                    Text(
                        stringResource(page.title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 24.dp, bottom = 16.dp),
                    )
                    Text(
                        stringResource(page.body),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                }
            }
            // page dots
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier.padding(4.dp).size(8.dp).clip(CircleShape).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (last && !hasLibrary) {
                    TextButton(onClick = { onDone(false) }, modifier = Modifier.testTag("guide_later")) { Text(stringResource(R.string.guide_later)) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        when {
                            !last -> scope.launch { if (eink) pager.scrollToPage(pager.currentPage + 1) else pager.animateScrollToPage(pager.currentPage + 1) }
                            else -> onDone(!hasLibrary)
                        }
                    },
                    modifier = Modifier.testTag("guide_next"),
                ) {
                    Text(
                        stringResource(
                            when {
                                !last -> R.string.guide_next
                                hasLibrary -> R.string.guide_done
                                else -> R.string.guide_add_library
                            },
                        ),
                    )
                }
            }
        }
    }
}
