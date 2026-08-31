package com.emre.wearbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.emre.wearbook.R
import com.emre.wearbook.books.Book
import com.emre.wearbook.books.BooksRepository
import com.emre.wearbook.data.PlayerPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** R28/R29/R31/R33/R34: TransformingLazyColumn with crown scrolling, per-book
 *  progress %, and long-press delete against a confirmation dialog. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onPlay: (Book) -> Unit, onOpenUploader: () -> Unit, onDelete: (Book) -> Unit) {
    val context = LocalContext.current
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var positions by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var durations by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(BooksRepository.list(context), PlayerPrefs.positions(context), PlayerPrefs.durations(context))
        }
        books = loaded.first
        positions = loaded.second
        durations = loaded.third
    }

    if (books.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.library_empty))
            Button(
                onClick = onOpenUploader,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(stringResource(R.string.library_uploader)) }
        }
        return
    }

    val rotaryBehavior = RotaryScrollableDefaults.behavior(listState)

    Box(Modifier.fillMaxSize()) {
        TimeText(Modifier.align(Alignment.TopCenter))
        TransformingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = 32.dp, bottom = 72.dp),
            rotaryScrollableBehavior = rotaryBehavior,
        ) {
            item { ListHeader { Text(stringResource(R.string.library_header)) } }
            items(count = books.size, key = { books[it].id }) { i ->
                val book = books[i]
                val pct = posPct(positions[book.id], durations[book.id])
                Text(
                    text = if (pct != null) "${book.title} · ${pct}%" else book.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 16.dp)
                        .combinedClickable(
                            onClick = { onPlay(book) },
                            onLongClick = { pendingDelete = book },
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Pinned footer: a last list item scrolled below the bezel is invisible
        // and untappable; this one is always on screen.
        Button(
            onClick = onOpenUploader,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 16.dp),
        ) { Text(stringResource(R.string.library_uploader)) }
    }

    // Plain compose Dialog: the m3 dialog slot APIs shifted between 1.5/1.6 and
    // this app needs nothing curved here — a centered confirm is enough.
    pendingDelete?.let { book ->
        Dialog(onDismissRequest = { pendingDelete = null }) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.library_delete_title))
                Text(
                    stringResource(R.string.library_delete_message, book.title),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                Button(onClick = {
                    onDelete(book)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
                Button(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

private fun posPct(posMs: Long?, durMs: Long?): Int? =
    if (posMs != null && durMs != null && durMs > 0) ((posMs * 100) / durMs).toInt()
    else null
