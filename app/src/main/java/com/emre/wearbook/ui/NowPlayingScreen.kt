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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.emre.wearbook.playback.PlaybackState
import com.emre.wearbook.playback.PlaybackUi
import com.emre.wearbook.playback.chapterIndexAt
import kotlinx.coroutines.delay

private val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_OPTIONS = listOf(15, 30, 60, 120)

@Composable
fun NowPlayingScreen(ui: PlaybackUi, onOpenChapters: () -> Unit) {
    val nowPlaying by PlaybackState.nowPlaying.collectAsState()
    val isPlaying by PlaybackState.isPlaying.collectAsState()
    val positionMs by PlaybackState.positionMs.collectAsState()
    val durationMs by PlaybackState.durationMs.collectAsState()
    val chapters by PlaybackState.chapters.collectAsState()
    val speed by PlaybackState.speed.collectAsState()
    val sleepEndMs by PlaybackState.sleepEndMs.collectAsState()
    val sleepArmed by PlaybackState.sleepMinutes.collectAsState()
    val error by PlaybackState.playbackError.collectAsState()

    // Tick once a minute so the sleep countdown stays fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
        error?.let {
            Text(
                text = "⚠ $it",
                color = Color.Red,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Chapters when present; otherwise -30s/+30s rewind/fast-forward.
            Button(
                onClick = {
                    if (chapters.isNotEmpty()) ui.skipToChapter((chapterIndex - 1).coerceAtLeast(0))
                    else ui.skipRelative(-30_000)
                },
                enabled = if (chapters.isNotEmpty()) chapterIndex > 0 else durationMs > 0,
            ) { Text("⏮") }
            Button(onClick = { ui.togglePlayPause() }) {
                Text(if (isPlaying) "⏸" else "▶")
            }
            Button(
                onClick = {
                    if (chapters.isNotEmpty()) ui.skipToChapter(chapterIndex + 1)
                    else ui.skipRelative(30_000)
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
                ui.setSpeed(next)
            }) { Text("%.2f×".format(speed)) }

            // Cycle from the armed option, not the shrunk remaining minutes:
            // 15 → 30 → 60 → 120 → off, mid-countdown included.
            Button(onClick = {
                val next = when (val idx = sleepArmed?.let { SLEEP_OPTIONS.indexOf(it) } ?: -1) {
                    -1 -> SLEEP_OPTIONS.first()
                    SLEEP_OPTIONS.lastIndex -> null // off
                    else -> SLEEP_OPTIONS[idx + 1]
                }
                ui.setSleepTimer(next)
            }) { Text(if (sleepEndMs != null) "Zzz ${sleepRemainingMin}m" else "Sleep") }
        }
    }
}
