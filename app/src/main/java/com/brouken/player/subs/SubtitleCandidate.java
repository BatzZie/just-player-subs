package com.brouken.player.subs;

import androidx.annotation.NonNull;

/** One subtitle found online. */
public final class SubtitleCandidate {
    public String id;
    public String url;
    public String lang;
    public String releaseName;
    public boolean hashMatch;
    /** Frame rate the subtitle was made for, in fps * 1000, or -1. */
    public int fpsMilli = -1;
    public ReleaseInfo release = new ReleaseInfo();

    // Filled in by SubtitleRanker
    public int langRank;
    public int score;

    public String label() {
        String name = releaseName != null && !releaseName.isEmpty() ? releaseName : id;
        return "[" + Languages.displayName(lang) + "] " + (hashMatch ? "✓ " : "") + name;
    }

    @NonNull
    @Override
    public String toString() {
        return lang + " score=" + score + (hashMatch ? " HASH" : "") + " fps=" + fpsMilli + " " + releaseName;
    }
}
