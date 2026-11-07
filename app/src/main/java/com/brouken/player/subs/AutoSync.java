package com.brouken.player.subs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds the offset between an online subtitle and a reference subtitle that is known to be in sync
 * (an embedded track of the video). Reference cue start times are collected while the video plays.
 * <p>
 * Works on cue start times only, so the reference may be in another language or even a bitmap (PGS) track.
 * Two confidence levels: a provisional estimate early on, and a final one that needs more evidence.
 * Thresholds were tuned by simulation on a real subtitle (noise, missing cues, unrelated references).
 */
final class AutoSync {

    static final int FIRST_ATTEMPT = 8;
    private static final long MAX_OFFSET_MS = 120_000;
    private static final long STEP_MS = 100;
    // About 60% of the lines of two independent subtitles start within 0.3 s of each other
    private static final long MATCH_MS = 350;

    enum Level { PROVISIONAL, FINAL }

    static final class Result {
        final long offsetMs;
        final Level level;

        Result(long offsetMs, Level level) {
            this.offsetMs = offsetMs;
            this.level = level;
        }
    }

    private final List<Long> refStarts = new ArrayList<>();
    private boolean lastEmpty = true;
    private long lastStart = -1;
    private double previousCoarse = Double.NaN;
    private int agreeing;

    /** Called with every cue change of the reference track. */
    void onCues(boolean empty, long timeMs) {
        if (!empty && lastEmpty && (lastStart < 0 || Math.abs(timeMs - lastStart) > 200)) {
            refStarts.add(timeMs);
            lastStart = timeMs;
        }
        lastEmpty = empty;
    }

    /** One-shot estimate from a complete list of reference start times (e.g. from the MKV index). */
    static Result estimateAll(List<Long> refStartsMs, long[] subStartsMs, double scale) {
        AutoSync s = new AutoSync();
        List<Long> sorted = new ArrayList<>(refStartsMs);
        Collections.sort(sorted);
        for (long t : sorted) {
            s.onCues(false, t);
            s.onCues(true, t + 1);
        }
        Result r = s.estimate(subStartsMs, scale);
        return r != null && r.level == Level.FINAL ? r : null;
    }

    /** A different online subtitle was chosen: earlier agreement doesn't count any more. */
    void resetAgreement() {
        previousCoarse = Double.NaN;
        agreeing = 0;
    }

    int collected() {
        return refStarts.size();
    }

    /** After a seek a cue may already be showing; don't count it as a new start. */
    void onSeek() {
        lastEmpty = false;
        lastStart = -1;
    }

    /** @return estimate (display = subtitle time * scale + offset) or null if there isn't enough evidence yet */
    Result estimate(long[] subStartsMs, double scale) {
        if (refStarts.size() < FIRST_ATTEMPT || subStartsMs.length == 0) return null;
        double[] subs = new double[subStartsMs.length];
        for (int i = 0; i < subs.length; i++) subs[i] = subStartsMs[i] * scale;
        Arrays.sort(subs);

        // For every candidate offset: how many reference cues have one of our cues within MATCH_MS
        int steps = (int) (2 * MAX_OFFSET_MS / STEP_MS) + 1;
        int[] matches = new int[steps];
        for (int k = 0; k < steps; k++) {
            double d = k * STEP_MS - MAX_OFFSET_MS;
            int count = 0;
            for (long r : refStarts) {
                int i = lowerBound(subs, r - d - MATCH_MS);
                if (i < subs.length && subs[i] <= r - d + MATCH_MS) count++;
            }
            matches[k] = count;
        }
        int best = 0;
        for (int k = 1; k < steps; k++) if (matches[k] > matches[best]) best = k;
        int second = 0;
        int margin = (int) (1500 / STEP_MS);
        for (int k = 0; k < steps; k++) {
            if (Math.abs(k - best) > margin) second = Math.max(second, matches[k]);
        }
        double coarse = best * STEP_MS - MAX_OFFSET_MS;
        int matched = matches[best];

        if (!Double.isNaN(previousCoarse) && Math.abs(coarse - previousCoarse) <= 300) agreeing++;
        else agreeing = 0;
        previousCoarse = coarse;

        int n = refStarts.size();
        Level level = null;
        if (matched >= Math.max(6, (int) Math.ceil(n * 0.35))) {
            if (matched >= second * 2.0 || (agreeing >= 3 && matched >= second * 1.5)) level = Level.FINAL;
            else if (matched >= second * 1.6 || (agreeing >= 2 && matched >= second * 1.3)) level = Level.PROVISIONAL;
        }
        android.util.Log.i(AutoSubs.TAG, "AutoSync: refs=" + refStarts.size() + " matched=" + matched
                + " second=" + second + " agreeing=" + agreeing + " coarse=" + (long) coarse + " -> " + level);
        if (level == null) return null;

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
        if (diffs.isEmpty()) return null;
        Collections.sort(diffs);
        return new Result(Math.round(diffs.get(diffs.size() / 2)), level);
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
