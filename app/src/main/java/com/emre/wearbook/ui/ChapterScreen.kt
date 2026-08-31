package com.emre.wearbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.emre.wearbook.playback.PlaybackState
import com.emre.wearbook.playback.PlaybackUi
import com.emre.wearbook.playback.chapterIndexAt

@Composable
fun ChapterScreen(ui: PlaybackUi) {
    val chapters by PlaybackState.chapters.collectAsState()
    val positionMs by PlaybackState.positionMs.collectAsState()
    val current = chapters.chapterIndexAt(positionMs)

    if (chapters.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No chapter metadata")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 40.dp),
    ) {
        item { ListHeader { Text("Chapters") } }
        itemsIndexed(chapters) { i, chapter ->
            Text(
                text = "${i + 1}. ${chapter.title}",
                color = if (i == current) Color.Cyan else Color.Unspecified,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ui.skipToChapter(i) }
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                maxLines = 2,
            )
        }
    }
}
