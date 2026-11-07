package autosubs.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds a small synthetic Matroska file in memory and reads its index like a remote file. */
class MkvIndexTest {

    private fun id(v: Int): ByteArray {
        val len = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
        return ByteArray(len) { ((v shr (8 * (len - 1 - it))) and 0xFF).toByte() }
    }

    private fun size(v: Long): ByteArray { // always 8 byte vint
        val b = ByteArray(8) { ((v shr (8 * (7 - it))) and 0xFF).toByte() }
        b[0] = 0x01
        return b
    }

    private fun el(idv: Int, vararg children: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { children.forEach { write(it) } }.toByteArray()
        return id(idv) + size(body.size.toLong()) + body
    }

    private fun uint(idv: Int, v: Long, bytes: Int = 8) = el(idv, ByteArray(bytes) { ((v shr (8 * (bytes - 1 - it))) and 0xFF).toByte() })
    private fun str(idv: Int, s: String) = el(idv, s.toByteArray())

    private fun file(subStarts: List<Long>, cuesPositionPatch: Boolean = true): ByteArray {
        val ebml = el(0x1A45DFA3, str(0x4282, "matroska"))
        val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = el(0x1654AE6B,
            el(0xAE, uint(0xD7, 1, 1), uint(0x83, 1, 1), str(0x86, "V_MPEGH/ISO/HEVC")),
            el(0xAE, uint(0xD7, 2, 1), uint(0x83, 17, 1), str(0x22B59C, "dut"), str(0x86, "S_TEXT/UTF8"), str(0x536E, "Nederlands")),
            el(0xAE, uint(0xD7, 3, 1), uint(0x83, 17, 1), str(0x86, "S_TEXT/UTF8"), uint(0x55AA, 1, 1)))
        val cluster = el(0x1F43B675, uint(0xE7, 0), ByteArray(200_000)) // stands in for the video data
        val cuePoints = ArrayList<ByteArray>()
        cuePoints.add(el(0xBB, uint(0xB3, 0), el(0xB7, uint(0xF7, 1, 1), uint(0xF1, 0))))
        for (t in subStarts) cuePoints.add(el(0xBB, uint(0xB3, t), el(0xB7, uint(0xF7, 2, 1), uint(0xF1, 0))))
        val cues = el(0x1C53BB6B, *cuePoints.toTypedArray())
        // SeekHead with a fixed size: position of Cues relative to the segment data
        fun seekHead(pos: Long) = el(0x114D9B74, el(0x4DBB, uint(0x53AB, 0x1C53BB6B.toLong(), 4), uint(0x53AC, pos)))
        val headLen = seekHead(0).size + info.size + tracks.size + cluster.size
        val segmentBody = seekHead(if (cuesPositionPatch) headLen.toLong() else 0) + info + tracks + cluster + cues
        return ebml + id(0x18538067) + size(segmentBody.size.toLong()) + segmentBody
    }

    private fun reader(data: ByteArray, failFirst: Boolean = false): MkvIndex.RangeReader {
        var calls = 0
        return MkvIndex.RangeReader { start, length, minUseful ->
            calls++
            if (failFirst && calls == 1) throw IOException("unexpected end of stream")
            val end = minOf(data.size.toLong(), start + length).toInt()
            val out = data.copyOfRange(start.toInt(), end)
            if (out.size < minUseful) throw IOException("short read")
            out
        }
    }

    @Test fun readsSubtitleStartTimes() {
        val starts = (1..500).map { it * 4_321L }
        val tracks = MkvIndex.read(reader(file(starts)))
        assertEquals(2, tracks.size)
        val dutch = tracks.first { it.number == 2 }
        assertEquals("dut", dutch.language)
        assertEquals("Nederlands", dutch.name)
        assertTrue(dutch.isText())
        assertEquals(starts, dutch.startsMs)
        val forced = tracks.first { it.number == 3 }
        assertTrue(forced.forced)
        assertEquals("eng", forced.language) // Matroska default
    }

    @Test fun indexAtTheVeryEndOfTheFile() {
        // The 64 KB cues request runs past the end of the file: the shorter answer must be used
        val tracks = MkvIndex.read(reader(file(listOf(1000L, 2000L, 3000L))))
        assertEquals(listOf(1000L, 2000L, 3000L), tracks.first { it.number == 2 }.startsMs)
    }

    @Test fun notMatroska() = assertEquals(0, MkvIndex.read(reader(ByteArray(100_000) { 0x47 })).size)

    @Test fun indexFeedsAutoSync() {
        // Irregular like real dialogue (a perfectly regular rhythm would make every multiple of it a valid offset)
        val rnd = kotlin.random.Random(3)
        var t = 30_000L
        val sub = LongArray(800) { t += 800 + rnd.nextLong(7000); t }
        val ref = sub.map { it - 1_850 }
        val tracks = MkvIndex.read(reader(file(ref)))
        val r = AutoSync.estimateAll(tracks.first { it.number == 2 }.startsMs, sub, 1.0)
        assertTrue(r != null && Math.abs(r.offsetMs + 1_850) < 100, "estimated ${r?.offsetMs}")
    }
}
