package com.brouken.player.subs;

import android.content.Context;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import autosubs.core.Http;

/**
 * Playback over the shared OkHttp client of the core (Happy Eyeballs: IPv6 and IPv4 in parallel; see
 * {@link Http}). The platform HttpURLConnection waited ~15 s per stream start on networks with broken IPv6.
 */
public final class Net {

    private Net() {}

    /** Same user agent as the platform HTTP stack, which streams were already tested with. */
    static void init() {
        String ua = System.getProperty("http.agent");
        if (ua != null && !ua.isEmpty()) Http.setUserAgent(ua);
    }

    /** Data source for the player: OkHttp for http(s), the default sources for files and content URIs. */
    public static DataSource.Factory dataSourceFactory(Context context) {
        init();
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(Http.getClient()).setUserAgent(Http.getUserAgent());
        return new DefaultDataSource.Factory(context, http);
    }
}
