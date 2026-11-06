package com.brouken.player.subs;

import android.net.Uri;

import java.util.List;

/** Stream URLs often contain debrid API tokens. Never log them in full. */
final class Redact {

    private Redact() {}

    static String url(Uri uri) {
        if (uri == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost());
        List<String> segments = uri.getPathSegments();
        for (int i = 0; i < segments.size(); i++) {
            String s = segments.get(i);
            boolean last = i == segments.size() - 1;
            sb.append('/').append(!last && s.length() >= 24 ? "<redacted>" : s);
        }
        if (uri.getQuery() != null) sb.append("?<redacted>");
        return sb.toString();
    }
}
