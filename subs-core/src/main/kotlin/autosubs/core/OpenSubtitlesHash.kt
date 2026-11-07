package autosubs.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles "moviehash": file size + sum of the first and last 64 KiB as little endian uint64 values.
 * Only used for local files (for streams it means extra requests on the same debrid link during start-up).
 */
object OpenSubtitlesHash {
    private const val CHUNK = 64 * 1024

    @JvmStatic
    @Throws(IOException::class)
    fun compute(reader: MkvIndex.RangeReader, size: Long): String {
        if (size < CHUNK * 2L) throw IOException("File too small for a hash: $size")
        val head = reader.read(0, CHUNK, CHUNK)
        val tail = reader.read(size - CHUNK, CHUNK, CHUNK)
        var h = size
        h += sum(head)
        h += sum(tail)
        return String.format("%016x", h)
    }

    internal fun sum(data: ByteArray): Long {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var s = 0L
        while (b.remaining() >= 8) s += b.long
        return s
    }
}
