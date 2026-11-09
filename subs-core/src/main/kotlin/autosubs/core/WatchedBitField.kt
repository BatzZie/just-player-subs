package autosubs.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Stremio's per-series "watched" field (library item state.watched), as in stremio-core's
 * stremio-watched-bitfield crate: "{anchor video id}:{anchor length}:{base64(zlib(bits))}".
 * Bit i (LSB first within each byte) belongs to the i-th video in [sortedVideoIds] order.
 */
class WatchedBitField private constructor(private val videoIds: List<String>, private val bits: BooleanArray) {

    fun get(videoId: String): Boolean = videoIds.indexOf(videoId).let { it >= 0 && bits[it] }

    fun set(videoId: String, watched: Boolean) {
        val i = videoIds.indexOf(videoId)
        if (i >= 0) bits[i] = watched
    }

    /** Serialized form; the anchor is the last watched video (or the first video when none is watched). */
    override fun toString(): String {
        val bytes = ByteArray((videoIds.size + 7) / 8)
        for (i in bits.indices) if (bits[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        var last = bits.indices.lastOrNull { bits[it] } ?: 0
        val anchor = videoIds.getOrNull(last) ?: "undefined"
        return "$anchor:${last + 1}:${pack(bytes)}"
    }

    companion object {
        /** Bits of [serialized] mapped onto [videoIds] (shifted via the anchor if episodes were added). */
        @JvmStatic
        fun parse(serialized: String?, videoIds: List<String>): WatchedBitField {
            val bits = BooleanArray(videoIds.size)
            if (serialized.isNullOrEmpty()) return WatchedBitField(videoIds, bits)
            val parts = serialized.split(':').toMutableList()
            if (parts.size < 3) return WatchedBitField(videoIds, bits)
            val packed = parts.removeAt(parts.size - 1)
            val anchorLength = parts.removeAt(parts.size - 1).toIntOrNull() ?: return WatchedBitField(videoIds, bits)
            val anchor = parts.joinToString(":")
            val values = try {
                unpack(packed)
            } catch (e: Exception) {
                return WatchedBitField(videoIds, bits)
            }
            val anchorIdx = videoIds.indexOf(anchor)
            if (anchorIdx < 0) return WatchedBitField(videoIds, bits) // like stremio-core: unknown anchor, blank
            val offset = anchorLength - anchorIdx - 1
            val oldLength = values.size * 8
            for (i in videoIds.indices) {
                val prev = i + offset
                if (prev in 0 until oldLength) bits[i] = (values[prev / 8].toInt() shr (prev % 8)) and 1 != 0
            }
            return WatchedBitField(videoIds, bits)
        }

        /** Video ids in Stremio's order: season, episode, release time (stable for ties). */
        @JvmStatic
        fun sortedVideoIds(videos: List<Video>): List<String> =
            videos.sortedWith(compareBy<Video>({ it.season ?: Long.MIN_VALUE }, { it.episode ?: Long.MIN_VALUE },
                { it.releasedMs ?: Long.MIN_VALUE })).map { it.id }

        class Video(
            @JvmField val id: String, @JvmField val season: Long?, @JvmField val episode: Long?, @JvmField val releasedMs: Long?,
            @JvmField val title: String? = null,
        )

        internal fun pack(bytes: ByteArray): String {
            val d = Deflater(6)
            d.setInput(bytes)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }

        internal fun unpack(packed: String): ByteArray {
            val inf = Inflater()
            inf.setInput(Base64.getDecoder().decode(packed))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            return out.toByteArray()
        }
    }
}
