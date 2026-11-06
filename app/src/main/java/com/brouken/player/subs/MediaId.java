package com.brouken.player.subs;

import androidx.annotation.NonNull;

/** Stremio style content id: tt123 for movies, tt123:S:E for episodes. */
public final class MediaId {
    public final String imdbId;
    public final int season;
    public final int episode;

    public MediaId(String imdbId, int season, int episode) {
        this.imdbId = imdbId;
        this.season = season;
        this.episode = episode;
    }

    public boolean isSeries() {
        return season >= 0 && episode >= 0;
    }

    public String type() {
        return isSeries() ? "series" : "movie";
    }

    public String videoId() {
        return isSeries() ? imdbId + ":" + season + ":" + episode : imdbId;
    }

    @NonNull
    @Override
    public String toString() {
        return type() + "/" + videoId();
    }
}
