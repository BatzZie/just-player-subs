package com.brouken.player.subs;

import java.io.IOException;
import java.util.List;

/** A place to search subtitles. More sources (e.g. the official OpenSubtitles API with a key) can be added later. */
public interface SubtitleSource {

    /**
     * @param hash     OpenSubtitles hash or null
     * @param size     file size or -1
     * @param fileName video file name or null
     */
    List<SubtitleCandidate> search(MediaId id, String hash, long size, String fileName, List<String> languages)
            throws IOException;

    /** Returns the subtitle file contents (any encoding). */
    byte[] download(SubtitleCandidate candidate) throws IOException;

    /** Short name shown in the subtitle list. */
    String name();
}
