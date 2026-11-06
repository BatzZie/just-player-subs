package com.brouken.player.subs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the offset between an online subtitle and a reference subtitle that is known to be in sync
 * (an embedded track of the video). Reference cue start times are collected while the video plays.
 * <p>
 * Works on cue start times only, so the reference may be in another language or even a bitmap (PGS) track.
 */
final class AutoSync {

    /** Reference cues needed before an estimate is attempted. */
    static final int WANTED = 16;
    private static final long MAX_OFFSET_MS = 120_000;
    private static final long BUCKET_MS = 100;
    private static final long MATCH_MS = 500;

    private final List<Long> refStarts = new ArrayList<>();
    private boolean lastEmpty = true;
    private long lastPosition = -1;

    /** Called with every cue change of the reference track. */
    void onCues(boolean empty, long positionMs) {
        if (!empty && lastEmpty) {
            // Ignore duplicates caused by re-sent cues at (almost) the same position
            if (lastPosition < 0 || Math.abs(positionMs - lastPosition) > 200) {
                refStarts.add(positionMs);
                lastPosition = positionMs;
            }
        }
        lastEmpty = empty;
    }

    int collected() {
        return refStarts.size();
    }

    /** Resets the "previous state" after a seek so a cue that was already showing isn't counted. */
    void onSeek() {
        lastEmpty = false;
        lastPosition = -1;
    }

    /**
     * @return offset in ms (display = subtitle time * scale + offset), or null if no clear match was found
     */
    Long estimate(long[] subStartsMs, double scale) {
        if (refStarts.size() < 8 || subStartsMs.length == 0) return null;
        double[] subs = new double[subStartsMs.length];
        for (int i = 0; i < subs.length; i++) subs[i] = subStartsMs[i] * scale;
        Arrays.sort(subs);

        // For every candidate offset: how many reference cues have one of our cues within MATCH_MS
        int steps = (int) (2 * MAX_OFFSET_MS / BUCKET_MS) + 1;
        int[] matches = new int[steps];
        for (int k = 0; k < steps; k++) {
            double d = k * BUCKET_MS - MAX_OFFSET_MS;
            int count = 0;
            for (long r : refStarts) {
                int i = lowerBound(subs, r - d - MATCH_MS);
                if (i < subs.length && subs[i] <= r - d + MATCH_MS) count++;
            }
            matches[k] = count;
        }
        int best = 0;
        for (int k = 1; k < steps; k++) if (matches[k] > matches[best]) best = k;
        // Best offset clearly away from the winner (not its own shoulders)
        int second = 0;
        int margin = (int) (1500 / BUCKET_MS);
        for (int k = 0; k < steps; k++) {
            if (Math.abs(k - best) > margin) second = Math.max(second, matches[k]);
        }
        double coarse = best * BUCKET_MS - MAX_OFFSET_MS;

        // Refine: median of the nearest differences around the winner
        List<Double> diffs = new ArrayList<>();
        for (long r : refStarts) {
            double bestDiff = Double.NaN;
            int from = lowerBound(subs, r - coarse - MATCH_MS);
            for (int i = from; i < subs.length && subs[i] <= r - coarse + MATCH_MS; i++) {
                double d = r - subs[i];
                if (Double.isNaN(bestDiff) || Math.abs(d - coarse) < Math.abs(bestDiff - coarse)) bestDiff = d;
            }
            if (!Double.isNaN(bestDiff)) diffs.add(bestDiff);
        }
        int matched = matches[best];
        // Reference and online subtitle are made by different people: many cues won't line up exactly.
        // Require a clear winner instead of a high match ratio.
        int needed = Math.max(6, (int) Math.ceil(refStarts.size() * 0.35));
        android.util.Log.i(AutoSubs.TAG, "AutoSync: refs=" + refStarts.size() + " matched=" + matched
                + " second=" + second + " coarse=" + (long) coarse);
        if (matched < needed || matched < second * 1.6 || diffs.isEmpty()) return null;
        java.util.Collections.sort(diffs);
        return Math.round(diffs.get(diffs.size() / 2));
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
