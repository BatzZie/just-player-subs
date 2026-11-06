package com.brouken.player.subs;

import androidx.annotation.NonNull;

/** What could be parsed from a release / file name. Fields are null or -1 when unknown. */
public final class ReleaseInfo {
    public String title;
    public int year = -1;
    public int season = -1;
    public int episode = -1;
    public String group;
    public String source;
    public String resolution;

    public boolean isEpisode() {
        return season >= 0 && episode >= 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "title=" + title + " year=" + year + " S" + season + "E" + episode
                + " group=" + group + " source=" + source + " res=" + resolution;
    }
}
