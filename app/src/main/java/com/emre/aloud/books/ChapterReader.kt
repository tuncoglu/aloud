package com.emre.aloud.books

import androidx.annotation.OptIn
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.Chapter as Media3Chapter
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.emre.aloud.playback.Chapter
import com.emre.aloud.util.Logg
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads chapters straight from a file, independently of playback.
 *
 * The player publishes the same chapters through `Player.Listener`, but only
 * once it is actually *playing*: ExoPlayer will not drive the extractor past
 * the header while it sits paused, so a book opened at a saved position — the
 * normal case — came up with an empty chapter list. Seeking also makes
 * `Mp4Extractor` drop what it had already collected.
 *
 * So the parsing is Media3's (verified to match ffmpeg chapter-for-chapter on a
 * shelf of real audiobooks); only the *driving* of it is ours, on an IO thread,
 * where nothing about the playback position can interfere.
 */
@OptIn(UnstableApi::class)
object ChapterReader {

    /** Header parsing is done well before this; the cap only stops a runaway. */
    private const val MAX_READS = 20_000

    /** Blocking; call from [kotlinx.coroutines.Dispatchers.IO]. */
    fun read(file: File): List<Chapter> = try {
        val extractor = if (file.extension.lowercase() == "mp3") {
            Mp3Extractor()
        } else {
            Mp4Extractor(DefaultSubtitleParserFactory())
        }
        val sink = FormatSink()
        extractor.init(sink)
        RandomAccessFile(file, "r").use { raf ->
            var position = 0L
            var input = DefaultExtractorInput(RafReader(raf), position, file.length())
            val seek = PositionHolder()
            var reads = 0
            while (reads++ < MAX_READS && sink.chapters().isEmpty() && !sink.sawSample) {
                val result = extractor.read(input, seek)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) {
                    position = seek.position
                    raf.seek(position)
                    input = DefaultExtractorInput(RafReader(raf), position, file.length())
                }
            }
        }
        extractor.release()
        sink.chapters()
    } catch (e: Exception) {
        Logg.d("chapter read failed for ${file.name}: $e")
        emptyList()
    }

    private class RafReader(private val raf: RandomAccessFile) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            raf.read(buffer, offset, length)
    }

    /**
     * Collects the formats the extractor publishes. Chapters ride the track
     * Format's metadata for both containers, so that is all we keep; samples
     * are discarded, and the first one ends the read because header parsing
     * (and therefore chapter extraction) is complete by then.
     */
    private class FormatSink : ExtractorOutput {
        private val formats = mutableListOf<Format>()
        var sawSample = false
            private set

        fun chapters(): List<Chapter> = formats
            .flatMap { f ->
                val m = f.metadata ?: return@flatMap emptyList()
                (0 until m.length()).mapNotNull { m.get(it) as? Media3Chapter }
            }
            .filterNot { it.isHidden }
            .map { Chapter(it.startTimeMs, it.endTimeMs, it.title?.value?.trim().orEmpty()) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) { formats += format }

            override fun sampleData(
                input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int,
            ): Int {
                val skipped = input.read(ByteArray(length), 0, length)
                if (skipped < 0) {
                    if (allowEndOfInput) return C_RESULT_END_OF_INPUT
                    throw EOFException()
                }
                return skipped
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                data.skipBytes(length)
            }

            override fun sampleMetadata(
                timeUs: Long, flags: Int, size: Int, offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                sawSample = true
            }
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private const val C_RESULT_END_OF_INPUT = -1
}
