package autosubs.core

/** Percent encoding as in RFC 3986 (spaces become %20, not +), like android.net.Uri.encode/decode. */
object UrlCodec {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    @JvmStatic
    @JvmOverloads
    fun encode(s: String, allow: String = ""): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && (c in UNRESERVED || c in allow)) sb.append(c)
            else sb.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    @JvmStatic
    fun decode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().encodeToByteArray())
            i++
        }
        return out.toByteArray().decodeToString()
    }

    /** Last path segment of a URL, decoded (query and fragment removed). */
    @JvmStatic
    fun lastPathSegment(url: String): String? {
        val noQuery = url.substringBefore('#').substringBefore('?')
        val path = noQuery.substringAfter("://", noQuery).substringAfter('/', "")
        val seg = path.trimEnd('/').substringAfterLast('/')
        return if (seg.isEmpty()) null else decode(seg)
    }
}
