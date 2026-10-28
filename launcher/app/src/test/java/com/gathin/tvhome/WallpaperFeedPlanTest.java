package com.gathin.tvhome;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class WallpaperFeedPlanTest {
    @Test public void namesAreSafeAndPrefixed() {
        assertEquals("auto_tmdb-movie-1.jpg", WallpaperFeedPlan.fileName("tmdb-movie-1"));
        String odd = WallpaperFeedPlan.fileName("a/b c");
        assertTrue(odd.startsWith("auto_a_b_c-") && odd.endsWith(".jpg"));
        assertNotEquals(WallpaperFeedPlan.fileName("a/b"), WallpaperFeedPlan.fileName("a b"));
        assertNull(WallpaperFeedPlan.fileName(" "));
    }

    @Test public void keepsNewestAndDedups() {
        Map<String, String> w = WallpaperFeedPlan.wanted(Arrays.asList("x", "x", "y", "z"), 2);
        assertEquals(Arrays.asList("auto_x.jpg", "auto_y.jpg"), new ArrayList<>(w.keySet()));
    }

    @Test public void staleNeverTouchesUserFiles() {
        List<String> s = WallpaperFeedPlan.stale(
                Arrays.asList("001.jpg", "025.jpg", "captions.txt", "auto_old.jpg", "auto_x.jpg", "auto_y.jpg.part"),
                new HashSet<>(Collections.singletonList("auto_x.jpg")));
        assertEquals(Collections.singletonList("auto_old.jpg"), s);
    }

    @Test public void captionsKeepUserLines() {
        Map<String, String> auto = new LinkedHashMap<>();
        auto.put("auto_x.jpg", "Cat\tin\nhat");
        auto.put("auto_y.jpg", "");
        List<String> out = WallpaperFeedPlan.mergeCaptions(
                Arrays.asList("# mine", "001.jpg\tSea", "auto_old.jpg\tOld"), auto);
        assertEquals(Arrays.asList("# mine", "001.jpg\tSea", "auto_x.jpg\tCat in hat"), out);
    }

    @Test public void dueRules() {
        long h = 60 * 60_000L, now = 100 * 24 * h;
        assertTrue(WallpaperFeedPlan.due(0, 0, now));
        assertFalse(WallpaperFeedPlan.due(0, now - h / 2, now));
        assertFalse(WallpaperFeedPlan.due(now - 2 * h, now - 2 * h, now));
        assertTrue(WallpaperFeedPlan.due(now - 25 * h, now - 25 * h, now));
    }

    @Test public void scaling() {
        assertEquals(2, WallpaperFeedPlan.sampleSize(3840, 2160, 1920, 1080));
        assertEquals(1, WallpaperFeedPlan.sampleSize(1920, 1080, 1920, 1080));
        assertArrayEquals(new int[]{1920, 1080}, WallpaperFeedPlan.fit(2560, 1440, 1920, 1080));
        assertArrayEquals(new int[]{800, 600}, WallpaperFeedPlan.fit(800, 600, 1920, 1080));
    }
}
