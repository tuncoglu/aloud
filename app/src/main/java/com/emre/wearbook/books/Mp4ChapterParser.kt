package com.emre.wearbook.books

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Minimal ISO-BMFF chapter parser for .m4b files. Supports the two formats
 * real-world M4B files use:
 *
 *  - Nero `chpl` atom (in moov/udta): absolute start times in 100ns units.
 *  - QuickTime chapter track (trak/tref/chap reference + text samples in the
 *    referenced track): Pascal-string samples with times from the sample table.
 *
 * Media3's default MP4 extractor path does not extract these on device, so we
 * read them directly from the file instead.
 */
object Mp4ChapterParser {

    data class Chapter(val startMs: Long, val endMs: Long, val title: String)

    fun parse(file: File): List<Chapter> {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val moov = findTopLevelBox(raf, "moov") ?: return emptyList()
                val moovChildren = boxChildren(raf, moov.payloadStart, moov.end)

                // 1. Nero chapters (chpl in udta)
                val udta = moovChildren.firstOrNull { it.type == "udta" }
                if (udta != null) {
                    val chpl = boxChildren(raf, udta.payloadStart, udta.end)
                        .firstOrNull { it.type == "chpl" }
                    if (chpl != null) {
                        parseChpl(raf, chpl.payloadStart, chpl.end)?.let { return it }
                    }
                }

                // 2. QuickTime chapter track
                val traks = moovChildren.filter { it.type == "trak" }
                val chapTrackId = traks.firstNotNullOfOrNull { trak ->
                    val tref = boxChildren(raf, trak.payloadStart, trak.end)
                        .firstOrNull { it.type == "tref" }
                    val chap = tref?.let {
                        boxChildren(raf, it.payloadStart, it.end).firstOrNull { c -> c.type == "chap" }
                    }
                    chap?.let { readInt(raf, it.payloadStart) }
                }
                if (chapTrackId != null) {
                    val chapterTrak = traks.firstOrNull { trak ->
                        val tkhd = boxChildren(raf, trak.payloadStart, trak.end)
                            .firstOrNull { it.type == "tkhd" }
                        tkhd?.let { readInt(raf, it.payloadStart + 8) } == chapTrackId // version/flags(4) + trackId(4)
                    }
                    if (chapterTrak != null) {
                        parseQuickTimeChapters(raf, chapterTrak)?.let { return it }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("WearBite", "chapter parse failed: $e")
        }
        return emptyList()
    }

    // --- box walking ---

    private class Box(val type: String, val start: Long, val payloadStart: Long, val end: Long)

    private fun readBox(raf: RandomAccessFile, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        raf.seek(pos)
        var size = raf.readInt().toLong() and 0xFFFFFFFFL
        val type = String(ByteArray(4).also { raf.readFully(it) }, StandardCharsets.US_ASCII)
        var headerSize = 8L
        if (size == 1L) { // 64-bit largesize
            size = raf.readLong()
            headerSize = 16L
        } else if (size == 0L) { // extends to end
            size = limit - pos
        }
        if (size < headerSize || pos + size > limit) return null
        return Box(type, pos, pos + headerSize, pos + size)
    }

    private fun findTopLevelBox(raf: RandomAccessFile, wanted: String): Box? {
        val fileLen = raf.length()
        var pos = 0L
        while (pos < fileLen) {
            val box = readBox(raf, pos, fileLen) ?: break
            if (box.type == wanted) return box
            pos = box.end
        }
        return null
    }

    private fun boxChildren(raf: RandomAccessFile, start: Long, end: Long): List<Box> {
        val out = mutableListOf<Box>()
        var pos = start
        while (pos < end) {
            val box = readBox(raf, pos, end) ?: break
            out += box
            pos = box.end
        }
        return out
    }

    private fun readInt(raf: RandomAccessFile, pos: Long): Int {
        raf.seek(pos)
        return raf.readInt()
    }

    // --- Nero chpl ---

    private fun parseChpl(raf: RandomAccessFile, start: Long, end: Long): List<Chapter>? {
        raf.seek(start)
        val version = raf.read()
        val flags = raf.read() shl 16 or (raf.read() shl 8) or raf.read()
        raf.read() // reserved byte
        val count = raf.readInt()
        if (count <= 0 || count > 10_000) return null
        val out = mutableListOf<Chapter>()
        for (i in 0 until count) {
            val startMs = raf.readLong() / 10_000 // 100ns units
            val titleLen = raf.read()
            if (titleLen < 0) return out
            val bytes = ByteArray(titleLen)
            raf.readFully(bytes)
            val title = String(bytes, StandardCharsets.UTF_8)
            out += Chapter(startMs, -1, title)
        }
        return out
    }

    // --- QuickTime chapter track ---

    private fun parseQuickTimeChapters(raf: RandomAccessFile, trak: Box): List<Chapter>? {
        val children = boxChildren(raf, trak.payloadStart, trak.end)
        val mdia = children.firstOrNull { it.type == "mdia" } ?: return null
        val mdiaChildren = boxChildren(raf, mdia.payloadStart, mdia.end)
        val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" } ?: return null
        raf.seek(mdhd.payloadStart)
        val version = raf.read()
        val timescale: Long = if (version == 1) {
            raf.seek(mdhd.payloadStart + 20)
            raf.readInt().toLong() and 0xFFFFFFFFL
        } else {
            raf.seek(mdhd.payloadStart + 12)
            raf.readInt().toLong() and 0xFFFFFFFFL
        }
        if (timescale <= 0) return null
        val minf = mdiaChildren.firstOrNull { it.type == "minf" } ?: return null
        val stbl = boxChildren(raf, minf.payloadStart, minf.end).firstOrNull { it.type == "stbl" } ?: return null
        val stblChildren = boxChildren(raf, stbl.payloadStart, stbl.end)
        val stts = stblChildren.firstOrNull { it.type == "stts" } ?: return null
        val stsz = stblChildren.firstOrNull { it.type == "stsz" } ?: return null

        // stts: version/flags(4) + entryCount(4), then (count, delta) pairs
        raf.seek(stts.payloadStart + 8)
        val entryCount = raf.readInt()
        if (entryCount <= 0 || entryCount > 100_000) return null
        val startTimesMs = ArrayList<Long>(entryCount)
        var t = 0L
        var sampleCount = 0
        for (i in 0 until entryCount) {
            val count = raf.readInt()
            val delta = raf.readInt()
            for (j in 0 until count) {
                startTimesMs.add(t * 1000 / timescale)
                t += delta
            }
            sampleCount += count
        }

        // stsz: version/flags(4) + sampleSize(4) + sampleCount(4), then sizes
        raf.seek(stsz.payloadStart + 12)
        val samples = raf.readInt()
        if (samples <= 0 || samples > 100_000) return null
        val sizes = IntArray(samples)
        for (i in 0 until samples) sizes[i] = raf.readInt()

        // samples are 2-byte-length-prefixed UTF-8 strings, read from mdat
        val out = mutableListOf<Chapter>()
        val mdats = mutableListOf<Box>()
        var pos = 0L
        val fileLen = raf.length()
        while (pos < fileLen) {
            val box = readBox(raf, pos, fileLen) ?: break
            if (box.type == "mdat") mdats += box
            pos = box.end
        }
        if (mdats.isEmpty()) return null
        var mdatIndex = 0
        var mdatPos = mdats[0].payloadStart
        val n = minOf(samples, startTimesMs.size)
        for (i in 0 until n) {
            val size = sizes[i]
            if (size < 2 || size > 4096) { mdatPos += size; continue }
            // skip to the sample inside mdat chain
            while (mdatPos + size > mdats[mdatIndex].end && mdatIndex < mdats.size - 1) {
                mdatIndex++
                mdatPos = mdats[mdatIndex].payloadStart
            }
            raf.seek(mdatPos)
            val len = raf.readUnsignedShort()
            val bytes = ByteArray(minOf(len, size - 2))
            raf.readFully(bytes)
            val title = String(bytes, StandardCharsets.UTF_8)
            val endMs = if (i + 1 < n) startTimesMs[i + 1] else -1
            out += Chapter(startTimesMs[i], endMs, title)
            mdatPos += size
        }
        return out
    }
}
