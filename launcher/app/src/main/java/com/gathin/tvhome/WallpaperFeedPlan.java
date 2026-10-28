package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure planning for the automatic wallpaper feed (wallpaper_auto_update): file names, which auto-downloaded
 * files to keep or delete, and the captions.txt merge. No Android types, so it is tested on the JVM.
 * Only files starting with {@link #PREFIX} are ever touched; pictures pushed by a theme or by hand are left alone.
 */
final class WallpaperFeedPlan {
    static final String PREFIX = "auto_";
    static final int KEEP = 30;

    private WallpaperFeedPlan() { }

    static boolean isAuto(String fileName) {
        return fileName != null && fileName.startsWith(PREFIX);
    }

    /** A stable file name for a feed id: auto_ + a filesystem-safe id (+ short hash when it had to be changed). */
    static String fileName(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String clean = id.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.length() > 60) clean = clean.substring(0, 60);
        if (!clean.equals(id.trim())) clean = clean + "-" + shortHash(id.trim());
        return PREFIX + clean + ".jpg";
    }

    static String shortHash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 4; i++) b.append(String.format(Locale.ROOT, "%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** The newest {@code keep} feed ids (feed order is newest first) as file name -> id, duplicates dropped. */
    static Map<String, String> wanted(List<String> ids, int keep) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String id : ids) {
            String name = fileName(id);
            if (name == null || result.containsKey(name)) continue;
            result.put(name, id);
            if (result.size() >= keep) break;
        }
        return result;
    }

    /** Auto-downloaded files in the folder that are no longer wanted. Non-auto files are never returned. */
    static List<String> stale(List<String> existing, Set<String> wanted) {
        List<String> result = new ArrayList<>();
        for (String name : existing) {
            if (isAuto(name) && !wanted.contains(name) && !name.endsWith(".part")) result.add(name);
        }
        return result;
    }

    /**
     * captions.txt after a fetch: every line that is not about an auto_ file is kept as it was (in order), then one
     * "file<TAB>caption" line per present auto file that has a caption. Tabs and newlines in captions become spaces.
     */
    static List<String> mergeCaptions(List<String> oldLines, Map<String, String> autoCaptions) {
        List<String> lines = new ArrayList<>();
        for (String line : oldLines) {
            if (line == null) continue;
            int tab = line.indexOf('\t');
            String file = tab > 0 ? line.substring(0, tab).trim() : "";
            if (!isAuto(file)) lines.add(line);
        }
        for (Map.Entry<String, String> e : autoCaptions.entrySet()) {
            String caption = e.getValue() == null ? "" : e.getValue().replaceAll("[\\t\\r\\n]+", " ").trim();
            if (!caption.isEmpty()) lines.add(e.getKey() + "\t" + caption);
        }
        return lines;
    }

    /** Whether a fetch should start now: never succeeded, or a day since success, and an hour since any attempt. */
    static boolean due(long lastSuccess, long lastAttempt, long now) {
        if (lastAttempt > 0 && now >= lastAttempt && now - lastAttempt < 60 * 60_000L) return false;
        return lastSuccess == 0 || now < lastSuccess || now - lastSuccess >= 24 * 60 * 60_000L;
    }

    /** inSampleSize so the decoded picture is still at least maxW x maxH (or the original when smaller). */
    static int sampleSize(int w, int h, int maxW, int maxH) {
        int s = 1;
        while (w / (s * 2) >= maxW && h / (s * 2) >= maxH) s *= 2;
        return s;
    }

    /** {width, height} that fits inside maxW x maxH keeping the aspect ratio; never upscales. */
    static int[] fit(int w, int h, int maxW, int maxH) {
        if (w <= maxW && h <= maxH) return new int[]{w, h};
        double r = Math.min(maxW / (double) w, maxH / (double) h);
        return new int[]{Math.max(1, (int) Math.round(w * r)), Math.max(1, (int) Math.round(h * r))};
    }
}
