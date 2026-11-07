package autosubs.core

import java.io.IOException

/**
 * Reads subtitle start times from a Matroska file's index (Cues) with two small range requests.
 *
 * mkvmerge writes a cue point for every subtitle block, so the Cues element holds the start time of every
 * embedded subtitle line. That gives a complete, exact sync reference within a second, without playing anything.
 */
object MkvIndex {

    /** Reads up to [length] bytes at [start]; may return fewer (end of file) if at least [minUseful] arrived. */
    fun interface RangeReader {
        @Throws(IOException::class)
        fun read(start: Long, length: Int, minUseful: Int): ByteArray
    }

    class SubtitleTrackInfo {
        @JvmField var number: Int = 0
        @JvmField var language: String = "eng" // Matroska default
        @JvmField var codec: String? = null
        @JvmField var name: String? = null
        @JvmField var forced: Boolean = false
        @JvmField val startsMs: MutableList<Long> = ArrayList()

        fun isText(): Boolean = codec?.startsWith("S_TEXT") == true

        override fun toString(): String =
            "#$number $language $codec" + (name?.let { " '$it'" } ?: "") + (if (forced) " forced" else "") + " cues=${startsMs.size}"
    }

    private const val ID_SEGMENT = 0x18538067
    private const val ID_SEEK_HEAD = 0x114D9B74
    private const val ID_SEEK = 0x4DBB
    private const val ID_SEEK_ID = 0x53AB
    private const val ID_SEEK_POSITION = 0x53AC
    private const val ID_INFO = 0x1549A966
    private const val ID_TIMECODE_SCALE = 0x2AD7B1
    private const val ID_TRACKS = 0x1654AE6B
    private const val ID_TRACK_ENTRY = 0xAE
    private const val ID_TRACK_NUMBER = 0xD7
    private const val ID_TRACK_TYPE = 0x83
    private const val ID_LANGUAGE = 0x22B59C
    private const val ID_LANGUAGE_BCP47 = 0x22B59D
    private const val ID_CODEC_ID = 0x86
    private const val ID_NAME = 0x536E
    private const val ID_FLAG_FORCED = 0x55AA
    private const val ID_CLUSTER = 0x1F43B675
    private const val ID_CUES = 0x1C53BB6B
    private const val ID_CUE_POINT = 0xBB
    private const val ID_CUE_TIME = 0xB3
    private const val ID_CUE_TRACK_POSITIONS = 0xB7
    private const val ID_CUE_TRACK = 0xF7

    private const val HEAD_BYTES = 512 * 1024
    private const val MAX_CUES_BYTES = 16 * 1024 * 1024

    @JvmStatic
    @Throws(IOException::class)
    fun read(url: String): List<SubtitleTrackInfo> =
        read(RangeReader { start, length, minUseful -> Http.range(url, start, length, minUseful) })

    /** @return subtitle tracks with their cue start times, or an empty list if the file has no usable index */
    @JvmStatic
    @Throws(IOException::class)
    fun read(reader: RangeReader): List<SubtitleTrackInfo> {
        // The header (SeekHead, Info, Tracks) is normally within the first few KB
        val head = reader.read(0, HEAD_BYTES, 16 * 1024)
        val p = Parser(head)
        // EBML header, then Segment
        var segmentData = -1L
        try {
            while (p.pos < head.size) {
                val id = p.readId()
                val size = p.readSize()
                if (id == ID_SEGMENT) {
                    segmentData = p.pos.toLong()
                    break
                }
                p.pos += size.toInt()
            }
        } catch (e: RuntimeException) {
            return emptyList() // not a Matroska file
        }
        if (segmentData < 0) return emptyList()

        var timecodeScale = 1_000_000L
        var cuesPosition = -1L
        val tracks = LinkedHashMap<Int, SubtitleTrackInfo>()
        p.pos = segmentData.toInt()
        // A partially received header is fine: use what was parsed until the data ran out
        try {
            while (p.pos < head.size) {
                val id = p.readId()
                val size = p.readSize()
                val start = p.pos
                if (id == ID_CLUSTER || size < 0 || start + size > head.size) break
                when (id) {
                    ID_SEEK_HEAD -> p.children(start, size) { cid, cstart, csize ->
                        if (cid != ID_SEEK) return@children
                        var seekId = 0L
                        var seekPos = -1L
                        p.children(cstart, csize) { sid, sstart, ssize ->
                            if (sid == ID_SEEK_ID) seekId = p.uint(sstart, ssize)
                            if (sid == ID_SEEK_POSITION) seekPos = p.uint(sstart, ssize)
                        }
                        if (seekId == ID_CUES.toLong() && seekPos >= 0) cuesPosition = seekPos
                    }
                    ID_INFO -> p.children(start, size) { cid, cstart, csize ->
                        if (cid == ID_TIMECODE_SCALE) timecodeScale = p.uint(cstart, csize)
                    }
                    ID_TRACKS -> p.children(start, size) { cid, cstart, csize ->
                        if (cid != ID_TRACK_ENTRY) return@children
                        val t = SubtitleTrackInfo()
                        var type = 0L
                        p.children(cstart, csize) { tid, tstart, tsize ->
                            when (tid) {
                                ID_TRACK_NUMBER -> t.number = p.uint(tstart, tsize).toInt()
                                ID_TRACK_TYPE -> type = p.uint(tstart, tsize)
                                ID_LANGUAGE, ID_LANGUAGE_BCP47 -> t.language = p.string(tstart, tsize)
                                ID_CODEC_ID -> t.codec = p.string(tstart, tsize)
                                ID_NAME -> t.name = p.string(tstart, tsize)
                                ID_FLAG_FORCED -> t.forced = p.uint(tstart, tsize) != 0L
                            }
                        }
                        if (type == 17L) tracks[t.number] = t
                    }
                }
                p.pos = (start + size).toInt()
            }
        } catch (e: RuntimeException) {
            CoreLog.i("MKV index: header parsed partially (${head.size} bytes)")
        }
        CoreLog.i("MKV index: header ${head.size} bytes, ${tracks.size} subtitle tracks, cues at $cuesPosition")
        if (cuesPosition < 0 || tracks.isEmpty()) return ArrayList(tracks.values)

        // Cues element: read its header first to learn the size, usually everything fits in one request
        val cuesStart = segmentData + cuesPosition
        val cuesHead = reader.read(cuesStart, 64 * 1024, 64)
        val c = Parser(cuesHead)
        if (c.readId() != ID_CUES) return ArrayList(tracks.values)
        val cuesSize = c.readSize()
        val headerLen = c.pos
        if (cuesSize < 0 || cuesSize > MAX_CUES_BYTES) return ArrayList(tracks.values)
        val total = (cuesSize + headerLen).toInt()
        val cues = if (total <= cuesHead.size) cuesHead else reader.read(cuesStart, total, total)
        val q = Parser(cues)
        q.children(headerLen, minOf(cuesSize, (cues.size - headerLen).toLong())) { id, start, size ->
            if (id != ID_CUE_POINT) return@children
            var time = -1L
            val numbers = ArrayList<Int>(2)
            q.children(start, size) { pid, pstart, psize ->
                if (pid == ID_CUE_TIME) time = q.uint(pstart, psize)
                else if (pid == ID_CUE_TRACK_POSITIONS) q.children(pstart, psize) { tid, tstart, tsize ->
                    if (tid == ID_CUE_TRACK) numbers.add(q.uint(tstart, tsize).toInt())
                }
            }
            if (time < 0) return@children
            for (n in numbers) tracks[n]?.startsMs?.add(time * timecodeScale / 1_000_000L)
        }
        return ArrayList(tracks.values)
    }

    /** Minimal EBML reader. Unknown sizes are returned as -1. */
    private class Parser(val b: ByteArray) {
        var pos = 0

        fun readId(): Int {
            val first = b[pos].toInt() and 0xFF
            val len = Integer.numberOfLeadingZeros(first) - 23
            check(len in 1..4) { "bad id" }
            var v = 0
            for (i in 0 until len) v = (v shl 8) or (b[pos + i].toInt() and 0xFF)
            pos += len
            return v
        }

        fun readSize(): Long {
            val first = b[pos].toInt() and 0xFF
            val len = Integer.numberOfLeadingZeros(first) - 23
            check(len in 1..8) { "bad size" }
            var v = (first and (0xFF shr len)).toLong()
            var allOnes = v == (0xFF shr len).toLong()
            for (i in 1 until len) {
                val x = b[pos + i].toInt() and 0xFF
                if (x != 0xFF) allOnes = false
                v = (v shl 8) or x.toLong()
            }
            pos += len
            return if (allOnes) -1 else v
        }

        fun children(start: Int, size: Long, visit: (id: Int, start: Int, size: Long) -> Unit) {
            val saved = pos
            pos = start
            val end = minOf(b.size.toLong(), start + size)
            while (pos < end) {
                val id: Int
                val s: Long
                try {
                    id = readId()
                    s = readSize()
                } catch (e: RuntimeException) {
                    break
                }
                val childStart = pos
                if (s < 0 || childStart + s > end) break
                visit(id, childStart, s)
                pos = (childStart + s).toInt()
            }
            pos = saved
        }

        fun uint(start: Int, size: Long): Long {
            var v = 0L
            for (i in 0 until size.toInt()) v = (v shl 8) or (b[start + i].toLong() and 0xFF)
            return v
        }

        fun string(start: Int, size: Long): String {
            var end = start
            while (end < start + size && b[end].toInt() != 0) end++
            return String(b, start, end - start, Charsets.UTF_8)
        }
    }
}
