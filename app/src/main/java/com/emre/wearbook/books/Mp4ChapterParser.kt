package com.emre.wearbook.books

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

private const val TAG = "WearBite"

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
                // Some real-world muxers nest moov inside moov; unwrap before
                // looking for udta/chapters (seen in Pollan m4b, 2026-08).
                var moovChildren = boxChildren(raf, moov.payloadStart, moov.end)
                while (moovChildren.size == 1 && moovChildren[0].type == "moov") {
                    moovChildren = boxChildren(raf, moovChildren[0].payloadStart, moovChildren[0].end)
                }

                // 1. Nero chapters (chpl in udta)
                val udta = moovChildren.firstOrNull { it.type == "udta" }
                if (udta != null) {
                    val chpl = boxChildren(raf, udta.payloadStart, udta.end)
                        .firstOrNull { it.type == "chpl" }
                    if (chpl != null) {
                        parseChpl(raf, chpl.payloadStart, chpl.end)?.let { return it }
                    }
                }

                // 2. QuickTime chapter track. Walk each trak's children once,
                // collecting (trakId, chapterRefId); the referencing trak can be
                // anywhere in order. v0/v1 tkhd: version/flags(4) + trackId(4).
                val traks = moovChildren.filter { it.type == "trak" }
                val trakInfos = traks.map { trak ->
                    val children = boxChildren(raf, trak.payloadStart, trak.end)
                    val tkhd = children.firstOrNull { it.type == "tkhd" }
                    val chap = children.firstOrNull { it.type == "tref" }?.let { tref ->
                        boxChildren(raf, tref.payloadStart, tref.end).firstOrNull { it.type == "chap" }
                    }
                    Triple(
                        trak,
                        tkhd?.let {
                            // v0/v1 tkhd: fullbox(4) + creation(4)+modification(4)
                            // (v1: 8+8) BEFORE trackId — offset 8 reads the mtime.
                            raf.seek(it.payloadStart)
                            val version = raf.read()
                            readInt(raf, it.payloadStart + if (version == 1) 20 else 12)
                        },
                        chap?.let { readInt(raf, it.payloadStart) },
                    )
                }
                val refTrakId = trakInfos.firstNotNullOfOrNull { it.third }
                val chapterTrak = refTrakId?.let { id -> trakInfos.firstOrNull { it.second == id }?.first }
                if (chapterTrak != null) {
                    parseQuickTimeChapters(raf, chapterTrak)?.let { return it }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "chapter parse failed: $e")
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

        // stts: version/flags(4) + entryCount(4), then (count, delta) pairs.
        // The entry count sits at +4; +8 is the first pair's count field.
        raf.seek(stts.payloadStart + 4)
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

        // Sample data is located via the chunk offset table (stco/co64) plus
        // per-chunk sample counts (stsc). The old mdat-sequential heuristic
        // breaks whenever the text track isn't the first thing in mdat or
        // padding precedes its chunks (seen in Pollan-style retail m4bs).
        val stsc = stblChildren.firstOrNull { it.type == "stsc" } ?: return null
        val stco = stblChildren.firstOrNull { it.type == "stco" }
            ?: stblChildren.firstOrNull { it.type == "co64" }
            ?: return null
        raf.seek(stsc.payloadStart + 4)
        val stscCount = raf.readInt()
        if (stscCount <= 0 || stscCount > 100_000) return null
        val runs = mutableListOf<LongArray>() // [firstChunk(1-based), samplesPerChunk]
        var prevFirstChunk = 0L
        for (i in 0 until stscCount) {
            val firstChunk = raf.readInt().toLong()
            val perChunk = raf.readInt().toLong()
            raf.readInt() // sampleDescriptionIndex, unused here
            if (firstChunk <= prevFirstChunk || perChunk <= 0) return null
            runs += longArrayOf(firstChunk, perChunk)
            prevFirstChunk = firstChunk
        }
        val isCo64 = stco.type == "co64"
        raf.seek(stco.payloadStart + 4)
        val chunkCount = raf.readInt()
        if (chunkCount <= 0 || chunkCount > 100_000) return null
        val chunkOffsets = LongArray(chunkCount)
        for (i in 0 until chunkCount) {
            chunkOffsets[i] = if (isCo64) raf.readLong()
            else raf.readInt().toLong() and 0xFFFFFFFFL
        }

        // Samples are 2-byte-length-prefixed UTF-8 strings, one per chunk.
        val out = mutableListOf<Chapter>()
        val n = minOf(samples, startTimesMs.size)
        var sampleIdx = 0
        var runIdx = 0
        for (chunkIdx in 0 until chunkCount) {
            val chunkNo = chunkIdx + 1
            // A stsc run covers every chunk from its firstChunk until the next
            // run begins (the final run extends to the last chunk).
            while (runIdx + 1 < runs.size && chunkNo >= runs[runIdx + 1][0]) runIdx++
            val perChunk = if (runIdx < runs.size && chunkNo >= runs[runIdx][0])
                runs[runIdx][1].toInt() else 0
            if (perChunk <= 0) continue
            var pos = chunkOffsets[chunkIdx]
            var inChunk = 0
            while (sampleIdx < n && inChunk < perChunk) {
                val size = sizes[sampleIdx]
                if (size < 2) return null // must at least hold the length prefix
                raf.seek(pos)
                val len = raf.readUnsignedShort()
                val bytes = ByteArray(minOf(len, size - 2))
                raf.readFully(bytes)
                val title = String(bytes, StandardCharsets.UTF_8)
                val endMs = if (sampleIdx + 1 < n) startTimesMs[sampleIdx + 1] else -1
                out += Chapter(startTimesMs[sampleIdx], endMs, title)
                pos += size
                sampleIdx++
                inChunk++
            }
        }
        return out
    }
}
