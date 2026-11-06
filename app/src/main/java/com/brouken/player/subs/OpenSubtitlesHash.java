package com.brouken.player.subs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * OpenSubtitles "moviehash": file size + sum of the first and last 64 KiB as little endian uint64 values.
 * Only two small range requests are made, the file itself is never downloaded.
 */
public final class OpenSubtitlesHash {

    private static final int CHUNK = 64 * 1024;

    public final String hash;
    public final long size;

    private OpenSubtitlesHash(String hash, long size) {
        this.hash = hash;
        this.size = size;
    }

    public static OpenSubtitlesHash compute(String url) throws IOException {
        Http.RangeResult head = range(url, 0);
        long size = head.totalSize;
        if (size < CHUNK * 2L) throw new IOException("Unknown or too small file size: " + size);
        // Reuse the redirect target (e.g. the Real-Debrid download URL) for the second request
        Http.RangeResult tail = range(head.finalUrl, size - CHUNK);
        long h = size;
        h += sum(head.data);
        h += sum(tail.data);
        return new OpenSubtitlesHash(String.format(Locale.ROOT, "%016x", h), size);
    }

    /** Some proxies drop the first connection while they start the stream; try a second time. */
    private static Http.RangeResult range(String url, long start) throws IOException {
        try {
            return Http.getRange(url, start, CHUNK);
        } catch (IOException first) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                throw first;
            }
            return Http.getRange(url, start, CHUNK);
        }
    }

    static long sum(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long s = 0;
        while (b.remaining() >= 8) s += b.getLong();
        return s;
    }
}
