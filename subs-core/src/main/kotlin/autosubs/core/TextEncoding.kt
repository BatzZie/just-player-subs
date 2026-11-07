package autosubs.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextEncoding {
    /** Files without valid UTF-8 are almost always Windows-1252 (Western European). UTF-16 with BOM is kept. */
    @JvmStatic
    fun toUtf8(data: ByteArray): ByteArray {
        if (data.size >= 2 && ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) ||
                (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))) return data
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
            data
        } catch (e: CharacterCodingException) {
            String(data, Charset.forName("windows-1252")).toByteArray(Charsets.UTF_8)
        }
    }
}
