package autosubs.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenSubtitlesHashTest {
    @Test fun sizePlusSums() {
        val size = 200_000L
        val data = ByteArray(size.toInt())
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(0, 5).putLong((size - 8).toInt(), 7)
        val reader = MkvIndex.RangeReader { start, length, _ -> data.copyOfRange(start.toInt(), start.toInt() + length) }
        assertEquals(String.format("%016x", size + 5 + 7), OpenSubtitlesHash.compute(reader, size))
    }
}
