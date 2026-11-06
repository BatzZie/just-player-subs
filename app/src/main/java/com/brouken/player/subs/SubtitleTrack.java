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
        data = toUtf8(data);
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

    /** Files without valid UTF-8 are almost always Windows-1252 (Western European). */
    static byte[] toUtf8(byte[] data) {
        if (data.length >= 2 && ((data[0] == (byte) 0xFF && data[1] == (byte) 0xFE)
                || (data[0] == (byte) 0xFE && data[1] == (byte) 0xFF))) {
            return data; // UTF-16 with BOM, the parsers handle it
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data));
            return data;
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(data, java.nio.charset.Charset.forName("windows-1252")).getBytes(StandardCharsets.UTF_8);
        }
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

    /**
     * Speed factor for subtitles made for a different frame rate (e.g. 25 fps PAL vs 23.976 fps).
     *
     * @param subFps   frame rate the subtitle was made for, or <= 0 if unknown
     * @param videoFps frame rate of the video, or <= 0 if unknown
     */
    public double detectScale(double subFps, double videoFps, long videoDurationMs) {
        long last = lastEndMs();
        boolean durationKnown = videoDurationMs > 0 && last > 0;

        if (subFps > 0 && videoFps > 0) {
            double f = subFps / videoFps;
            if (isKnownRatio(f)) {
                // Only trust the metadata if the subtitle still fits the video after scaling
                if (!durationKnown || last * f <= videoDurationMs + 2000) return f;
            }
        }
        // Subtitle runs well past the end of the video: probably made for a slower frame rate
        if (durationKnown && videoDurationMs > 10 * 60_000 && last > videoDurationMs + 20_000) {
            double[] candidates = {23.976 / 25.0, 24.0 / 25.0};
            for (double f : candidates) {
                if (last * f <= videoDurationMs + 2000) return f;
            }
        }
        return 1.0;
    }

    private static boolean isKnownRatio(double f) {
        // Only the ~4% PAL speed-up; 24 vs 23.976 fps metadata is too unreliable to act on
        double[] known = {25.0 / 23.976, 25.0 / 24.0};
        for (double k : known) {
            if (Math.abs(f - k) < 0.0004 || Math.abs(f - 1.0 / k) < 0.0004) return true;
        }
        return false;
    }
}
