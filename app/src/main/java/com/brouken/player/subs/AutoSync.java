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
    private static final long MATCH_MS = 300;

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
        if (refStarts.size() < 6 || subStartsMs.length == 0) return null;
        double[] subs = new double[subStartsMs.length];
        for (int i = 0; i < subs.length; i++) subs[i] = subStartsMs[i] * scale;
        Arrays.sort(subs);

        int buckets = (int) (2 * MAX_OFFSET_MS / BUCKET_MS) + 1;
        int[] votes = new int[buckets];
        for (long r : refStarts) {
            boolean[] voted = new boolean[buckets];
            int from = lowerBound(subs, r - MAX_OFFSET_MS);
            for (int i = from; i < subs.length && subs[i] <= r + MAX_OFFSET_MS; i++) {
                double d = r - subs[i];
                int b = (int) Math.round((d + MAX_OFFSET_MS) / BUCKET_MS);
                if (b >= 0 && b < buckets && !voted[b]) {
                    voted[b] = true;
                    votes[b]++;
                }
            }
        }
        // Peak of the smoothed histogram (a match can fall in a neighbouring bucket)
        int best = -1, bestVotes = 0, second = 0;
        for (int b = 0; b < buckets; b++) {
            int v = votes[b] + (b > 0 ? votes[b - 1] : 0) + (b + 1 < buckets ? votes[b + 1] : 0);
            if (v > bestVotes) {
                bestVotes = v;
                best = b;
            }
        }
        if (best < 0) return null;
        double coarse = best * BUCKET_MS - MAX_OFFSET_MS;
        for (int b = 0; b < buckets; b++) {
            if (Math.abs(b - best) <= 10) continue;
            int v = votes[b] + (b > 0 ? votes[b - 1] : 0) + (b + 1 < buckets ? votes[b + 1] : 0);
            second = Math.max(second, v);
        }

        // Refine: median of the differences that agree with the peak
        List<Double> diffs = new ArrayList<>();
        int matched = 0;
        for (long r : refStarts) {
            double bestDiff = Double.NaN;
            int from = lowerBound(subs, r - coarse - MATCH_MS);
            for (int i = from; i < subs.length && subs[i] <= r - coarse + MATCH_MS; i++) {
                double d = r - subs[i];
                if (Double.isNaN(bestDiff) || Math.abs(d - coarse) < Math.abs(bestDiff - coarse)) bestDiff = d;
            }
            if (!Double.isNaN(bestDiff)) {
                diffs.add(bestDiff);
                matched++;
            }
        }
        int needed = Math.max(5, (int) Math.ceil(refStarts.size() * 0.45));
        android.util.Log.i(AutoSubs.TAG, "AutoSync: refs=" + refStarts.size() + " matched=" + matched
                + " peak=" + bestVotes + " second=" + second + " coarse=" + (long) coarse);
        if (matched < needed || bestVotes < second * 1.5) return null;
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
