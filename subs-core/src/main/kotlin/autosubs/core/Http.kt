package autosubs.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client and small blocking helpers.
 *
 * OkHttp tries IPv6 and IPv4 in parallel ("Happy Eyeballs"). The platform HttpURLConnection tries addresses one
 * by one and waits for each to time out: on networks where IPv6 to some hosts is broken (seen with Torrentio
 * behind Cloudflare) that added ~15 s to every stream start.
 */
object Http {

    /** Set by the app (Android: the system "http.agent", the user agent streams were tested with). */
    @JvmStatic
    @Volatile
    var userAgent: String = "AutoSubs"

    @JvmStatic
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .fastFallback(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    class Response(@JvmField val code: Int, @JvmField val body: String)

    /** Request with extra headers and an optional JSON body. Does not throw on HTTP errors. */
    @JvmStatic
    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String>?, jsonBody: String?): Response {
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        headers?.forEach { (k, v) -> b.header(k, v) }
        b.method(method, jsonBody?.toRequestBody("application/json".toMediaType()))
        client.newCall(b.build()).execute().use { r ->
            return Response(r.code, readUpTo(r.body.byteStream(), 4 * 1024 * 1024).decodeToString())
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getString(url: String): String = getBytes(url, 8 * 1024 * 1024).decodeToString()

    @JvmStatic
    @Throws(IOException::class)
    fun getBytes(url: String, maxBytes: Int): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP " + r.code)
            return readUpTo(r.body.byteStream(), maxBytes)
        }
    }

    /**
     * Range request [start, start+length) with retries. Debrid servers sometimes close a connection early
     * ("unexpected end of stream"), e.g. when a range runs past the end of the file; whatever arrived is kept
     * if it is at least [minUseful] bytes.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun range(url: String, start: Long, length: Int, minUseful: Int): ByteArray {
        var last: IOException? = null
        for (attempt in 1..3) {
            val out = ByteArray(length)
            var total = 0
            val req = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$start-${start + length - 1}")
                .build()
            var error: IOException? = null
            try {
                client.newCall(req).execute().use { r ->
                    if (r.code != 206) throw IOException("Range not supported (HTTP ${r.code})")
                    val input = r.body.byteStream()
                    while (total < length) {
                        val n = input.read(out, total, length - total)
                        if (n == -1) break
                        total += n
                    }
                }
            } catch (e: IOException) {
                error = e
                CoreLog.i("Range $start+$length attempt $attempt got $total bytes: $e")
            }
            if (error == null || total >= minUseful) return if (total == length) out else out.copyOf(total)
            last = error
            if (attempt < 3) {
                try {
                    Thread.sleep(400L * attempt)
                } catch (e: InterruptedException) {
                    throw error
                }
            }
        }
        throw last ?: IOException("range failed")
    }

    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray = input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n == -1) break
            out.write(buf, 0, n)
            total += n
        }
        out.toByteArray()
    }
}
