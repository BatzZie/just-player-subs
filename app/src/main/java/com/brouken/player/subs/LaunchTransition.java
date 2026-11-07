package com.brouken.player.subs;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

/**
 * When Stremio starts the player, keep showing Stremio's own loading screen (poster and pulsing logo) until the
 * first video frame is really there, then fade the player in.
 * <p>
 * The player screen starts translucent (theme Theme.Player.Launch, Android 11+) with its content invisible. On the
 * first rendered frame (or an error, or after a timeout) the content fades in and the window becomes opaque.
 * Started any other way, the player is made opaque immediately and behaves as before.
 */
public final class LaunchTransition implements Player.Listener {

    private static final String STREMIO_PREFIX = "com.stremio";
    private static final long TIMEOUT_MS = 25_000;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean waiting;

    private LaunchTransition(Activity activity) {
        this.activity = activity;
    }

    /** Call in onCreate, after setContentView. */
    static LaunchTransition begin(Activity activity) {
        LaunchTransition t = new LaunchTransition(activity);
        String caller = activity.getCallingPackage();
        boolean fromStremio = caller != null && caller.startsWith(STREMIO_PREFIX);
        if (Build.VERSION.SDK_INT >= 30 && fromStremio) {
            t.waiting = true;
            content(activity).setAlpha(0f);
            t.main.postDelayed(() -> t.reveal("timeout"), TIMEOUT_MS);
            Log.i(AutoSubs.TAG, "Launched by " + caller + ": showing its loading screen until the first frame");
        } else {
            t.makeOpaque();
        }
        return t;
    }

    private static View content(Activity activity) {
        return activity.findViewById(android.R.id.content);
    }

    @Override
    public void onRenderedFirstFrame() {
        reveal("first frame");
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        reveal("error"); // the player must be visible to show the error
    }

    /** Also used when the player has nothing to show (e.g. no media). */
    void reveal(String why) {
        if (!waiting) return;
        waiting = false;
        main.removeCallbacksAndMessages(null);
        Log.i(AutoSubs.TAG, "Player visible (" + why + ")");
        content(activity).animate().alpha(1f).setDuration(250).withEndAction(this::makeOpaque).start();
    }

    private void makeOpaque() {
        content(activity).setAlpha(1f);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                activity.setTranslucent(false);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
