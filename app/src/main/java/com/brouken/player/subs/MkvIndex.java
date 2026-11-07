package com.brouken.player.subs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Request;

/**
 * Reads subtitle start times from a Matroska file's index (Cues) with two small range requests.
 * <p>
 * mkvmerge writes a cue point for every subtitle block, so the Cues element holds the start time of every embedded
 * subtitle line. That gives a complete, exact sync reference within a second, without playing anything.
 */
public final class MkvIndex {

    private static final int ID_SEGMENT = 0x18538067;
    private static final int ID_SEEK_HEAD = 0x114D9B74;
    private static final int ID_SEEK = 0x4DBB;
    private static final int ID_SEEK_ID = 0x53AB;
    private static final int ID_SEEK_POSITION = 0x53AC;
    private static final int ID_INFO = 0x1549A966;
    private static final int ID_TIMECODE_SCALE = 0x2AD7B1;
    private static final int ID_TRACKS = 0x1654AE6B;
    private static final int ID_TRACK_ENTRY = 0xAE;
    private static final int ID_TRACK_NUMBER = 0xD7;
    private static final int ID_TRACK_TYPE = 0x83;
    private static final int ID_LANGUAGE = 0x22B59C;
    private static final int ID_LANGUAGE_BCP47 = 0x22B59D;
    private static final int ID_CODEC_ID = 0x86;
    private static final int ID_NAME = 0x536E;
    private static final int ID_FLAG_FORCED = 0x55AA;
    private static final int ID_CLUSTER = 0x1F43B675;
    private static final int ID_CUES = 0x1C53BB6B;
    private static final int ID_CUE_POINT = 0xBB;
    private static final int ID_CUE_TIME = 0xB3;
    private static final int ID_CUE_TRACK_POSITIONS = 0xB7;
    private static final int ID_CUE_TRACK = 0xF7;

    private static final int HEAD_BYTES = 512 * 1024;
    private static final int MAX_CUES_BYTES = 16 * 1024 * 1024;

    public static final class SubtitleTrackInfo {
        public int number;
        public String language = "eng"; // Matroska default
        public String codec;
        public String name;
        public boolean forced;
        public final List<Long> startsMs = new ArrayList<>();

        boolean isText() {
            return codec != null && codec.startsWith("S_TEXT");
        }

        @Override
        public String toString() {
            return "#" + number + " " + language + " " + codec + (name != null ? " '" + name + "'" : "")
                    + (forced ? " forced" : "") + " cues=" + startsMs.size();
        }
    }

    private MkvIndex() {}

    /** @return subtitle tracks with their cue start times, or an empty list if the file has no usable index */
    public static List<SubtitleTrackInfo> read(String url) throws IOException {
        byte[] head = range(url, 0, HEAD_BYTES);
        Parser p = new Parser(head);
        // EBML header, then Segment
        long segmentData = -1;
        while (p.pos < head.length) {
            int id = p.readId();
            long size = p.readSize();
            if (id == ID_SEGMENT) {
                segmentData = p.pos;
                break;
            }
            p.pos += size;
        }
        if (segmentData < 0) return new ArrayList<>();

        long timecodeScale = 1_000_000;
        long cuesPosition = -1;
        Map<Integer, SubtitleTrackInfo> tracks = new HashMap<>();
        p.pos = (int) segmentData;
        while (p.pos < head.length) {
            int id = p.readId();
            long size = p.readSize();
            int start = p.pos;
            if (id == ID_CLUSTER || size < 0 || start + size > head.length) break;
            if (id == ID_SEEK_HEAD) {
                long[] cues = {-1};
                p.children(start, size, (cid, cstart, csize) -> {
                    if (cid != ID_SEEK) return;
                    long[] seekId = {0}, seekPos = {-1};
                    p.children(cstart, csize, (sid, sstart, ssize) -> {
                        if (sid == ID_SEEK_ID) seekId[0] = p.uint(sstart, ssize);
                        if (sid == ID_SEEK_POSITION) seekPos[0] = p.uint(sstart, ssize);
                    });
                    if (seekId[0] == ID_CUES) cues[0] = seekPos[0];
                });
                if (cues[0] >= 0) cuesPosition = cues[0];
            } else if (id == ID_INFO) {
                long[] scale = {timecodeScale};
                p.children(start, size, (cid, cstart, csize) -> {
                    if (cid == ID_TIMECODE_SCALE) scale[0] = p.uint(cstart, csize);
                });
                timecodeScale = scale[0];
            } else if (id == ID_TRACKS) {
                p.children(start, size, (cid, cstart, csize) -> {
                    if (cid != ID_TRACK_ENTRY) return;
                    SubtitleTrackInfo t = new SubtitleTrackInfo();
                    long[] type = {0};
                    p.children(cstart, csize, (tid, tstart, tsize) -> {
                        if (tid == ID_TRACK_NUMBER) t.number = (int) p.uint(tstart, tsize);
                        else if (tid == ID_TRACK_TYPE) type[0] = p.uint(tstart, tsize);
                        else if (tid == ID_LANGUAGE) t.language = p.string(tstart, tsize);
                        else if (tid == ID_LANGUAGE_BCP47) t.language = p.string(tstart, tsize);
                        else if (tid == ID_CODEC_ID) t.codec = p.string(tstart, tsize);
                        else if (tid == ID_NAME) t.name = p.string(tstart, tsize);
                        else if (tid == ID_FLAG_FORCED) t.forced = p.uint(tstart, tsize) != 0;
                    });
                    if (type[0] == 17) tracks.put(t.number, t);
                });
            }
            p.pos = (int) (start + size);
        }
        if (cuesPosition < 0 || tracks.isEmpty()) return new ArrayList<>(tracks.values());

        // Cues element: read its header first to learn the size, usually everything fits in one request
        long cuesStart = segmentData + cuesPosition;
        byte[] cuesHead = range(url, cuesStart, 64 * 1024);
        Parser c = new Parser(cuesHead);
        if (c.readId() != ID_CUES) return new ArrayList<>(tracks.values());
        long cuesSize = c.readSize();
        int headerLen = c.pos;
        if (cuesSize < 0 || cuesSize > MAX_CUES_BYTES) return new ArrayList<>(tracks.values());
        byte[] cues = cuesSize + headerLen <= cuesHead.length ? cuesHead
                : range(url, cuesStart, (int) (cuesSize + headerLen));
        Parser q = new Parser(cues);
        final long scale = timecodeScale;
        q.children(headerLen, Math.min(cuesSize, cues.length - headerLen), (id, start, size) -> {
            if (id != ID_CUE_POINT) return;
            long[] time = {-1};
            List<Integer> trackNumbers = new ArrayList<>(2);
            q.children(start, size, (pid, pstart, psize) -> {
                if (pid == ID_CUE_TIME) time[0] = q.uint(pstart, psize);
                else if (pid == ID_CUE_TRACK_POSITIONS) {
                    q.children(pstart, psize, (tid, tstart, tsize) -> {
                        if (tid == ID_CUE_TRACK) trackNumbers.add((int) q.uint(tstart, tsize));
                    });
                }
            });
            if (time[0] < 0) return;
            for (int n : trackNumbers) {
                SubtitleTrackInfo t = tracks.get(n);
                if (t != null) t.startsMs.add(time[0] * scale / 1_000_000L);
            }
        });
        return new ArrayList<>(tracks.values());
    }

    private static byte[] range(String url, long start, int length) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", Net.userAgent())
                .header("Range", "bytes=" + start + "-" + (start + length - 1))
                .build();
        try (okhttp3.Response r = Net.client().newCall(req).execute()) {
            if (r.code() != 206) throw new IOException("Range not supported (HTTP " + r.code() + ")");
            byte[] out = new byte[length];
            int total = 0;
            java.io.InputStream in = r.body().byteStream();
            int n;
            while (total < length && (n = in.read(out, total, length - total)) != -1) total += n;
            if (total == length) return out;
            byte[] shorter = new byte[total];
            System.arraycopy(out, 0, shorter, 0, total);
            return shorter;
        }
    }

    /** Minimal EBML reader. Unknown sizes are returned as -1. */
    private static final class Parser {
        interface Visitor {
            void visit(int id, int start, long size);
        }

        final byte[] b;
        int pos;

        Parser(byte[] b) {
            this.b = b;
        }

        int readId() {
            int first = b[pos] & 0xFF;
            int len = Integer.numberOfLeadingZeros(first) - 23;
            if (len < 1 || len > 4) throw new IllegalStateException("bad id");
            int v = 0;
            for (int i = 0; i < len; i++) v = (v << 8) | (b[pos + i] & 0xFF);
            pos += len;
            return v;
        }

        long readSize() {
            int first = b[pos] & 0xFF;
            int len = Integer.numberOfLeadingZeros(first) - 23;
            if (len < 1 || len > 8) throw new IllegalStateException("bad size");
            long v = first & (0xFF >> len);
            boolean allOnes = v == (0xFF >> len);
            for (int i = 1; i < len; i++) {
                int x = b[pos + i] & 0xFF;
                if (x != 0xFF) allOnes = false;
                v = (v << 8) | x;
            }
            pos += len;
            return allOnes ? -1 : v;
        }

        void children(int start, long size, Visitor v) {
            int saved = pos;
            pos = start;
            long end = Math.min(b.length, start + size);
            while (pos < end) {
                int id;
                long s;
                try {
                    id = readId();
                    s = readSize();
                } catch (RuntimeException e) {
                    break;
                }
                int childStart = pos;
                if (s < 0 || childStart + s > end) break;
                v.visit(id, childStart, s);
                pos = (int) (childStart + s);
            }
            pos = saved;
        }

        long uint(int start, long size) {
            long v = 0;
            for (int i = 0; i < size; i++) v = (v << 8) | (b[start + i] & 0xFF);
            return v;
        }

        String string(int start, long size) {
            int end = start;
            while (end < start + size && b[end] != 0) end++;
            return new String(b, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
