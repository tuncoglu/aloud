package com.emre.wearbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.emre.wearbook.playback.PlayerManager
import com.emre.wearbook.playback.chapterIndexAt
import kotlinx.coroutines.delay

private val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_OPTIONS = listOf(15, 30, 60, 120)

@Composable
fun NowPlayingScreen(manager: PlayerManager, onOpenChapters: () -> Unit) {
    val nowPlaying by manager.nowPlaying.collectAsState()
    val isPlaying by manager.isPlaying.collectAsState()
    val positionMs by manager.positionMs.collectAsState()
    val durationMs by manager.durationMs.collectAsState()
    val chapters by manager.chapters.collectAsState()
    val speed by manager.speed.collectAsState()
    val sleepEndMs by manager.sleepEndMs.collectAsState()

    // Tick once a minute so the sleep countdown stays fresh.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepEndMs) {
        while (sleepEndMs != null) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val chapterIndex = remember(positionMs, chapters) {
        chapters.chapterIndexAt(positionMs)
    }
    val sleepRemainingMin = sleepEndMs?.let { ((it - now) / 60_000).toInt().coerceAtLeast(1) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = nowPlaying?.title ?: "",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (chapters.isNotEmpty())
                "ch ${(chapterIndex + 1).coerceAtLeast(1)}/${chapters.size} · ${fmtTime(positionMs)}/${fmtTime(durationMs)}"
            else
                "${fmtTime(positionMs)}/${fmtTime(durationMs)}",
            maxLines = 1,
        )

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Chapters when present; otherwise -30s/+30s rewind/fast-forward.
            Button(
                onClick = {
                    if (chapters.isNotEmpty()) manager.skipToChapter((chapterIndex - 1).coerceAtLeast(0))
                    else manager.skipRelative(-30_000)
                },
                enabled = if (chapters.isNotEmpty()) chapterIndex > 0 else durationMs > 0,
            ) { Text("⏮") }
            Button(onClick = { manager.togglePlayPause() }) {
                Text(if (isPlaying) "⏸" else "▶")
            }
            Button(
                onClick = {
                    if (chapters.isNotEmpty()) manager.skipToChapter(chapterIndex + 1)
                    else manager.skipRelative(30_000)
                },
                enabled = if (chapters.isNotEmpty()) chapterIndex < chapters.size - 1 else durationMs > 0,
            ) { Text("⏭") }
        }

        Button(
            onClick = onOpenChapters,
            modifier = Modifier.padding(top = 8.dp),
            enabled = chapters.isNotEmpty(),
        ) { Text("Chapters") }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                val next = SPEED_OPTIONS[(SPEED_OPTIONS.indexOf(speed) + 1) % SPEED_OPTIONS.size]
                manager.setSpeed(next)
            }) { Text("%.2f×".format(speed)) }

            Button(onClick = {
                val next = if (sleepEndMs == null) SLEEP_OPTIONS.first()
                else {
                    val idx = SLEEP_OPTIONS.indexOf((sleepRemainingMin ?: 0).coerceAtLeast(SLEEP_OPTIONS.first()))
                    SLEEP_OPTIONS.getOrElse(idx + 1) { -1 } // -1 -> off
                }
                manager.setSleepTimer(if (next == -1) null else next)
            }) { Text(if (sleepRemainingMin != null) "Zzz ${sleepRemainingMin}m" else "Sleep") }
        }
    }
}
