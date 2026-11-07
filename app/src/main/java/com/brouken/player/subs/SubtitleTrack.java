package com.brouken.player.subs;

import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.ssa.SsaParser;
import androidx.media3.extractor.text.subrip.SubripParser;
import androidx.media3.extractor.text.webvtt.WebvttParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A parsed subtitle file. Rendered by AutoSubs itself, so offset and speed changes apply instantly. */
public final class SubtitleTrack {

    private static final class Entry {
        final long startUs;
        final long endUs;
        final List<Cue> cues;

        Entry(long startUs, long endUs, List<Cue> cues) {
            this.startUs = startUs;
            this.endUs = endUs;
            this.cues = cues;
        }
    }

    private final List<Entry> entries;
    private final long[] starts;

    private SubtitleTrack(List<Entry> entries) {
        this.entries = entries;
        this.starts = new long[entries.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = entries.get(i).startUs;
    }

    public static SubtitleTrack parse(byte[] data) {
        data = autosubs.core.TextEncoding.toUtf8(data);
        SubtitleParser parser;
        String head = new String(data, 0, Math.min(data.length, 2048), StandardCharsets.UTF_8);
        if (head.startsWith("﻿")) head = head.substring(1);
        if (head.trim().startsWith("WEBVTT")) {
            parser = new WebvttParser();
        } else if (head.contains("[Script Info]") || head.contains("[V4+ Styles]") || head.contains("[Events]")) {
            parser = new SsaParser();
        } else {
            parser = new SubripParser();
        }
        List<CuesWithTiming> raw = new ArrayList<>();
        parser.parse(data, SubtitleParser.OutputOptions.allCues(), raw::add);
        Collections.sort(raw, (a, b) -> Long.compare(a.startTimeUs, b.startTimeUs));

        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            CuesWithTiming c = raw.get(i);
            if (c.cues.isEmpty() || c.startTimeUs == C.TIME_UNSET) continue;
            long end = c.durationUs != C.TIME_UNSET ? c.startTimeUs + c.durationUs
                    : (i + 1 < raw.size() ? raw.get(i + 1).startTimeUs : c.startTimeUs + 5_000_000L);
            entries.add(new Entry(c.startTimeUs, end, c.cues));
        }
        return new SubtitleTrack(entries);
    }

    /** Start times of all cues in ms (subtitle time, before scale and offset). */
    public long[] startTimesMs() {
        long[] out = new long[starts.length];
        for (int i = 0; i < starts.length; i++) out[i] = starts[i] / 1000;
        return out;
    }

    public int size() {
        return entries.size();
    }

    public long lastEndMs() {
        long max = 0;
        for (Entry e : entries) max = Math.max(max, e.endUs);
        return max / 1000;
    }

    /**
     * Cues to show at player position {@code positionMs}. Display time = subtitle time * scale + offset.
     */
    public List<Cue> cuesAt(long positionMs, double scale, long offsetMs) {
        long t = (long) (((positionMs - offsetMs) * 1000L) / scale);
        int idx = upperBound(t) - 1;
        if (idx < 0) return Collections.emptyList();
        List<Cue> out = null;
        // Look back a little for overlapping cues
        for (int i = idx; i >= 0 && i > idx - 30; i--) {
            Entry e = entries.get(i);
            if (e.endUs > t) {
                if (out == null) out = new ArrayList<>();
                out.addAll(0, e.cues);
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    /** First index with start > t. */
    private int upperBound(long t) {
        int lo = 0, hi = starts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Speed factor for subtitles made for a different frame rate, see {@link autosubs.core.FrameRate}. */
    public double detectScale(double subFps, double videoFps, long videoDurationMs) {
        return autosubs.core.FrameRate.detectScale(lastEndMs(), subFps, videoFps, videoDurationMs);
    }
}
