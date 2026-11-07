package com.brouken.player.subs;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** Small blocking HTTP helper on the shared OkHttp client (see {@link Net}). */
final class Http {

    private Http() {}

    static final class Response {
        int code;
        String body;
    }

    /** Request with extra headers and an optional JSON body. Does not throw on HTTP errors. */
    static Response request(String method, String url, Map<String, String> headers, String jsonBody)
            throws IOException {
        Request.Builder b = new Request.Builder().url(url).header("User-Agent", Net.userAgent());
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
        }
        RequestBody body = jsonBody == null ? null
                : RequestBody.create(jsonBody, MediaType.get("application/json"));
        b.method(method, body);
        try (okhttp3.Response r = Net.client().newCall(b.build()).execute()) {
            Response out = new Response();
            out.code = r.code();
            ResponseBody rb = r.body();
            out.body = new String(readUpTo(rb.byteStream(), 4 * 1024 * 1024), StandardCharsets.UTF_8);
            return out;
        }
    }

    static String getString(String url) throws IOException {
        return new String(getBytes(url, 8 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    static byte[] getBytes(String url, int maxBytes) throws IOException {
        Request req = new Request.Builder().url(url).header("User-Agent", Net.userAgent()).build();
        try (okhttp3.Response r = Net.client().newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
            return readUpTo(r.body().byteStream(), maxBytes);
        }
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
