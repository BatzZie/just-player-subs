package com.brouken.player.subs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small blocking HTTP helper. Uses the system user agent, the same one the player uses for streaming. */
final class Http {

    static final int TIMEOUT_MS = 8000;

    private Http() {}

    static final class RangeResult {
        String finalUrl;
        long totalSize = -1;
        byte[] data;
    }

    static final class Response {
        int code;
        String body;
    }

    /** Request with extra headers and an optional JSON body. Does not throw on HTTP errors. */
    static Response request(String method, String url, java.util.Map<String, String> headers, String jsonBody)
            throws IOException {
        HttpURLConnection c = open(url);
        try {
            c.setRequestMethod(method);
            if (headers != null) {
                for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            Response r = new Response();
            r.code = c.getResponseCode();
            InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = is == null ? "" : new String(readUpTo(is, 4 * 1024 * 1024), StandardCharsets.UTF_8);
            return r;
        } finally {
            c.disconnect();
        }
    }

    static String getString(String url) throws IOException {
        return new String(getBytes(url, 8 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    static byte[] getBytes(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            return readUpTo(c.getInputStream(), maxBytes);
        } finally {
            c.disconnect();
        }
    }

    /** Fetches exactly [start, start+length). Fails if the server ignores the Range header. */
    static RangeResult getRange(String url, long start, int length) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));
        try {
            int code = c.getResponseCode();
            if (code != 206) throw new IOException("Range not supported (HTTP " + code + ")");
            RangeResult r = new RangeResult();
            r.finalUrl = c.getURL().toString();
            String contentRange = c.getHeaderField("Content-Range");
            if (contentRange != null && contentRange.contains("/")) {
                try {
                    r.totalSize = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            r.data = readUpTo(c.getInputStream(), length);
            if (r.data.length != length) throw new IOException("Short range read: " + r.data.length);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private static byte[] readUpTo(InputStream in, int maxBytes) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int n;
            while (total < maxBytes && (n = is.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        }
    }
}
