package com.brouken.player.subs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses names like "Show.S02E05.1080p.WEB.H264-GROUP.mkv" or "The Batman (2022).mkv". */
public final class ReleaseNameParser {

    private static final Pattern EXTENSION = Pattern.compile(
            "(?i)\\.(mkv|mp4|m4v|avi|ts|m2ts|webm|mov|wmv|mpg|mpeg|srt|ass|ssa|vtt|sub)$");
    // "-GROUP" at the end, optionally followed by a tag like "[EZTVx.to]"
    private static final Pattern GROUP = Pattern.compile("-([A-Za-z0-9]{2,20})(?:\\[[^\\]]*\\])?\\s*$");
    private static final Set<String> NOT_A_GROUP = new HashSet<>(Arrays.asList(
            "dl", "rip", "web", "x264", "x265", "h264", "h265", "hevc", "hd", "sd", "dts", "aac", "ac3"));
    private static final Pattern EPISODE_SXXEYY = Pattern.compile("(?i)\\bS(\\d{1,2})\\s?E(\\d{1,3})\\b");
    private static final Pattern EPISODE_NXNN = Pattern.compile("(?i)\\b(\\d{1,2})x(\\d{2,3})\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private static final Pattern QUALITY = Pattern.compile(
            "(?i)\\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|web ?dl|webrip|web|bluray|blu ray|bdrip|brrip|bdremux|remux"
                    + "|hdtv|dvdrip|dvd|hdrip|x264|x265|h ?264|h ?265|hevc|repack|proper|multi|nordic|complete)\\b");
    private static final Pattern RESOLUTION = Pattern.compile("(?i)\\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd)\\b");

    private ReleaseNameParser() {}

    public static ReleaseInfo parse(String name) {
        ReleaseInfo info = new ReleaseInfo();
        if (name == null) return info;
        name = EXTENSION.matcher(name.trim()).replaceFirst("");

        Matcher g = GROUP.matcher(name);
        if (g.find() && !NOT_A_GROUP.contains(g.group(1).toLowerCase(Locale.ROOT))) {
            info.group = g.group(1);
        }

        // Normalise separators and brackets to spaces
        String n = name.replaceAll("[._\\[\\](){}]+", " ").replaceAll("\\s+", " ").trim();

        int cut = n.length();
        Matcher e = EPISODE_SXXEYY.matcher(n);
        if (!e.find()) {
            e = EPISODE_NXNN.matcher(n);
            if (!e.find()) e = null;
        }
        if (e != null) {
            info.season = Integer.parseInt(e.group(1));
            info.episode = Integer.parseInt(e.group(2));
            cut = Math.min(cut, e.start());
        }

        Matcher q = QUALITY.matcher(n);
        int qualityStart = q.find() ? q.start() : n.length();
        cut = Math.min(cut, qualityStart);

        // Last year before the quality/episode markers, but never at position 0 (title "2012" etc.)
        Matcher y = YEAR.matcher(n);
        int yearStart = -1;
        while (y.find()) {
            if (y.start() > cut) break;
            if (y.start() == 0) continue;
            info.year = Integer.parseInt(y.group(1));
            yearStart = y.start();
        }
        if (yearStart > 0) cut = Math.min(cut, yearStart);

        String title = n.substring(0, cut).replaceAll("[\\s-]+$", "").trim();
        info.title = title.isEmpty() ? null : title;

        info.source = source(n);
        Matcher r = RESOLUTION.matcher(n);
        if (r.find()) {
            String res = r.group(1).toLowerCase(Locale.ROOT);
            info.resolution = (res.equals("4k") || res.equals("uhd")) ? "2160p" : res;
        }
        return info;
    }

    /** Normalised source: BluRay, WEB, HDTV, DVD, HDRip, CAM or null. */
    public static String source(String text) {
        if (text == null) return null;
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[._\\-\\[\\]()]+", " ") + " ";
        if (t.matches(".*\\b(bluray|blu ray|bdrip|brrip|bdremux|remux|bd)\\b.*")) return "BluRay";
        if (t.matches(".*\\b(web ?dl|webrip|web|amzn|nf|atv|dsnp|hmax|hulu|pcok|skst|itunes|aptv)\\b.*")) return "WEB";
        if (t.matches(".*\\bhdtv\\b.*")) return "HDTV";
        if (t.matches(".*\\b(dvdrip|dvd|dvd5|dvd9)\\b.*")) return "DVD";
        if (t.matches(".*\\bhdrip\\b.*")) return "HDRip";
        if (t.matches(".*\\b(cam|hdcam|ts|telesync|hdts)\\b.*")) return "CAM";
        return null;
    }
}
