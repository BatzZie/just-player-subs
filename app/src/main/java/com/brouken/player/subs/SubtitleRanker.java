package com.brouken.player.subs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Filters on the wanted languages and sorts best first. */
public final class SubtitleRanker {

    private SubtitleRanker() {}

    public static List<SubtitleCandidate> rank(List<SubtitleCandidate> all, ReleaseInfo video, List<String> languages) {
        List<SubtitleCandidate> out = new ArrayList<>();
        for (SubtitleCandidate c : all) {
            int rank = -1;
            for (int i = 0; i < languages.size(); i++) {
                if (Languages.matches(c.lang, languages.get(i))) {
                    rank = i;
                    break;
                }
            }
            if (rank < 0) continue;
            c.langRank = rank;
            c.score = score(c, video);
            out.add(c);
        }
        // Stable sort keeps the addon's order (download count) for equal scores
        Collections.sort(out, (a, b) -> a.langRank != b.langRank
                ? Integer.compare(a.langRank, b.langRank)
                : Integer.compare(b.score, a.score));
        return out;
    }

    static int score(SubtitleCandidate c, ReleaseInfo video) {
        int score = 0;
        if (c.hashMatch) score += 100;
        if (c.machineTranslated) score -= 15;
        ReleaseInfo r = c.release;
        if (video != null && r != null) {
            if (video.group != null && r.group != null && video.group.equalsIgnoreCase(r.group)) score += 30;
            if (video.source != null && video.source.equals(r.source)) score += 10;
            else if (video.source != null && r.source != null && !video.source.equals(r.source)) score -= 2;
            if (video.resolution != null && video.resolution.equals(r.resolution)) score += 3;
            if (video.isEpisode() && r.isEpisode()
                    && (video.season != r.season || video.episode != r.episode)) score -= 50;
        }
        if ("CAM".equals(r == null ? null : r.source)) score -= 20;
        return score;
    }
}
