package com.brouken.player.subs;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

import java.util.Arrays;

/**
 * Switches the TV to the refresh rate of the video (e.g. 23.976 Hz for films), like Stremio's own player does.
 * Replaces Just Player's "Auto frame rate matching", which measures the frame rate with a second connection to
 * the stream (MediaExtractor) and did nothing for debrid streams. Here the frame rate comes from the frames the
 * player already shows (about 4 s of them); playback pauses while the TV switches so audio
 * passthrough isn't cut off.
 */
final class FrameRateSwitcher {
    /** Just Player's setting, reused (same meaning, working implementation). */
    static final String KEY = "frameRateMatching";
    /** About 4 s of frames: MKV times are whole ms, and 24 vs 23.976 fps differ only 0.1% */
    private static final int SAMPLES = 96;
    private static final long SWITCH_TIMEOUT_MS = 4000;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private final long[] pts = new long[SAMPLES + 1];
    private int count;
    private volatile boolean done;
    private DisplayManager.DisplayListener listener;
    private Runnable resume;

    FrameRateSwitcher(Activity activity) {
        this.activity = activity;
    }

    /**
     * @param newVideo false when Just Player made a new player for the same video: keep going if not done yet
     */
    void attach(Player p, boolean enabled, boolean newVideo) {
        detach();
        if (newVideo) {
            done = !enabled;
            count = 0;
        }
        if (done || !(p instanceof ExoPlayer)) return;
        player = (ExoPlayer) p;
        player.setVideoFrameMetadataListener(frames);
    }

    void detach() {
        if (player != null) player.clearVideoFrameMetadataListener(frames);
        player = null;
        stopWaiting();
    }

    private final VideoFrameMetadataListener frames = (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
        if (done) return;
        if (format != null && format.frameRate > 0) {
            done = true;
            float fps = format.frameRate;
            main.post(() -> switchTo(fps, "container"));
            return;
        }
        if (count > 0 && presentationTimeUs <= pts[count - 1]) count = 0; // seek or reorder: start over
        pts[count++] = presentationTimeUs;
        if (count <= SAMPLES) return;
        done = true;
        long[] d = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) d[i] = pts[i + 1] - pts[i];
        Arrays.sort(d);
        long median = d[SAMPLES / 2];
        // A steady stream: nearly all frames the same distance apart (variable frame rate: leave the TV alone).
        // MKV times are whole ms, so 23.976 fps alternates 41 and 42 ms: allow that.
        long low = d[SAMPLES / 6], high = d[SAMPLES - 1 - SAMPLES / 6];
        if (median <= 0 || high - low > Math.max(1500, median / 20)) {
            Log.i(AutoSubs.TAG, "Frame rate: not steady (" + low + ".." + high + " us), display unchanged");
            return;
        }
        // The average over all frames, not the median (that would be 42 ms = 23.81 fps)
        float fps = frameRate(pts, SAMPLES);
        main.post(() -> switchTo(fps, "measured"));
    };

    static float frameRate(long[] pts, int intervals) {
        return normalize(intervals * 1_000_000f / (pts[intervals] - pts[0]));
    }

    /** Snaps to the usual rates (23.976, 24, 25, 29.97, 30, 50, 59.94, 60). */
    static float normalize(float fps) {
        float[] common = {24000f / 1001, 24f, 25f, 30000f / 1001, 30f, 50f, 60000f / 1001, 60f};
        float best = fps;
        float bestDiff = 0.0015f;
        for (float c : common) {
            float diff = Math.abs(fps - c) / c;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = c;
            }
        }
        return best;
    }

    /** The mode with the same resolution whose refresh rate is a whole multiple of the frame rate (highest wins). */
    static Display.Mode bestMode(Display.Mode[] modes, Display.Mode active, float fps) {
        Display.Mode best = null;
        for (Display.Mode m : modes) {
            if (m.getPhysicalWidth() != active.getPhysicalWidth() || m.getPhysicalHeight() != active.getPhysicalHeight()) continue;
            float ratio = m.getRefreshRate() / fps;
            if (ratio < 0.999f || Math.abs(ratio - Math.round(ratio)) > 0.002f * Math.round(ratio)) continue;
            if (best == null || m.getRefreshRate() > best.getRefreshRate()) best = m;
        }
        return best;
    }

    private void switchTo(float fps, String how) {
        if (player == null) return;
        Window window = activity.getWindow();
        Display display = window.getDecorView().getDisplay();
        if (display == null) return;
        Display.Mode active = display.getMode();
        Display.Mode best = bestMode(display.getSupportedModes(), active, fps);
        Log.i(AutoSubs.TAG, String.format(java.util.Locale.ROOT, "Frame rate %.3f (%s), display %.3f Hz -> %s", fps, how,
                active.getRefreshRate(), best == null ? "no matching mode" : String.format(java.util.Locale.ROOT, "%.3f Hz (mode %d)", best.getRefreshRate(), best.getModeId())));
        if (best == null || best.getModeId() == active.getModeId()) return;

        // Pause during the switch (HDMI re-syncs, audio passthrough would drop out), continue when it's done
        final boolean wasPlaying = player.getPlayWhenReady();
        final ExoPlayer p = player;
        if (wasPlaying) p.setPlayWhenReady(false);
        DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        resume = () -> {
            stopWaiting();
            if (wasPlaying && player == p) p.setPlayWhenReady(true);
        };
        listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) { }
            @Override public void onDisplayChanged(int displayId) {
                Display d = window.getDecorView().getDisplay();
                if (d != null && d.getMode().getModeId() == best.getModeId()) {
                    Log.i(AutoSubs.TAG, "Display switched to " + best.getRefreshRate() + " Hz");
                    // Give the TV a moment to show the picture again
                    main.postDelayed(resume, 300);
                }
            }
        };
        if (dm != null) dm.registerDisplayListener(listener, main);
        main.postDelayed(resume, SWITCH_TIMEOUT_MS);

        WindowManager.LayoutParams lp = window.getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        window.setAttributes(lp);
    }

    private void stopWaiting() {
        if (resume != null) main.removeCallbacks(resume);
        if (listener != null) {
            DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) dm.unregisterDisplayListener(listener);
            listener = null;
        }
    }
}
