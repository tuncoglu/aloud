package com.emre.wearbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.emre.wearbook.books.Book
import com.emre.wearbook.books.BooksRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(onPlay: (Book) -> Unit, onOpenUploader: () -> Unit) {
    val context = LocalContext.current
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }

    LaunchedEffect(Unit) {
        books = withContext(Dispatchers.IO) { BooksRepository.list(context) }
    }

    if (books.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No books yet")
            Button(
                onClick = onOpenUploader,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Uploader") }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 40.dp),
    ) {
        item { ListHeader { Text("My audiobooks") } }
        items(books, key = { it.id }) { book ->
            Text(
                text = book.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(book) }
                    .padding(vertical = 10.dp, horizontal = 16.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            Text(
                text = "Uploader…",
                color = Color.Gray,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenUploader }
                    .padding(vertical = 10.dp, horizontal = 16.dp),
            )
        }
    }
}
