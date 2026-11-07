package com.brouken.player.subs;

import android.content.Context;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * One shared OkHttp client for playback and subtitle requests.
 * <p>
 * OkHttp tries IPv6 and IPv4 in parallel ("Happy Eyeballs"). The platform HttpURLConnection that Media3 uses by
 * default tries addresses one by one and waits for each to time out: on networks where IPv6 to some hosts is broken
 * (seen with Torrentio behind Cloudflare) that added ~15 s to every start of a stream.
 */
public final class Net {

    private static OkHttpClient client;

    private Net() {}

    public static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .fastFallback(true)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return client;
    }

    /** Same user agent as the platform HTTP stack, which streams were already tested with. */
    static String userAgent() {
        String ua = System.getProperty("http.agent");
        return ua == null || ua.isEmpty() ? "JustPlayerSubs" : ua;
    }

    /** Data source for the player: OkHttp for http(s), the default sources for files and content URIs. */
    public static DataSource.Factory dataSourceFactory(Context context) {
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(client()).setUserAgent(userAgent());
        return new DefaultDataSource.Factory(context, http);
    }
}
