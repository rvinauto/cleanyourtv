package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * wallpaper_auto_update: about once a day, while Home is shown and there is a network, downloads the newest
 * pictures of the wallpaper feed into files/wallpapers/ as auto_*.jpg (at most 1920x1080), keeps the newest
 * {@link WallpaperFeedPlan#KEEP} and deletes only older auto_ files. Never touches other files.
 */
final class WallpaperFeed {
    static final String TAG = "TvHome.Wallpaper";
    static final String CONFIG_KEY = "wallpaper_auto_update";
    private static final String[] FEEDS = {
            "https://cdn.jsdelivr.net/gh/rvinauto/cleanyourtv@wallpaper-feed/feed-mixed.json",
            "https://cdn.jsdelivr.net/gh/rvinauto/cleanyourtv@wallpaper-feed/feed.json",
    };
    private static final int MAX_FEED_BYTES = 512 * 1024;
    private static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_SAVED_BYTES = 10 * 1024 * 1024;
    private static final AtomicBoolean running = new AtomicBoolean();

    private WallpaperFeed() { }

    /**
     * Whether the feed is on for this config.json: its wallpaper_auto_update, else on when the theme rotates
     * (wallpaper_rotation_minutes > 0). No config.json, or "wallpaper": false, means off.
     */
    static boolean enabled(JSONObject json) {
        if (json == null || !json.optBoolean("wallpaper", true)) return false;
        if (json.has(CONFIG_KEY)) return json.optBoolean(CONFIG_KEY, false);
        return json.optInt("wallpaper_rotation_minutes", 0) > 0;
    }

    /** Starts a background fetch when the feed is on and one is due; {@code done} runs on that thread after new files. */
    static void maybeRun(Context context, Runnable done) {
        Context app = context.getApplicationContext();
        File dir = Config.dir(app);
        if (dir == null) return;
        SharedPreferences prefs = app.getSharedPreferences("wallpaper_feed", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!WallpaperFeedPlan.due(prefs.getLong("success", 0), prefs.getLong("attempt", 0), now)) return;
        if (!online(app)) {
            Log.d(TAG, "auto update due but no network; will retry");
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                JSONObject json = readConfig(new File(dir, Config.FILE_NAME));
                if (!enabled(json)) {
                    Log.d(TAG, "auto update off (" + CONFIG_KEY + " false, no rotation, or no config.json)");
                    prefs.edit().putLong("attempt", System.currentTimeMillis()).apply();
                    return;
                }
                prefs.edit().putLong("attempt", System.currentTimeMillis()).apply();
                int changed = fetch(new File(dir, "wallpapers"));
                prefs.edit().putLong("success", System.currentTimeMillis()).apply();
                if (changed > 0 && done != null) done.run();
            } catch (Exception e) {
                Log.w(TAG, "auto update failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                running.set(false);
            }
        }, "wallpaper-feed").start();
    }

    private static boolean online(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static JSONObject readConfig(File file) {
        try {
            if (!file.isFile() || file.length() > 64 * 1024) return null;
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns how many files were added or deleted. */
    private static int fetch(File folder) throws Exception {
        JSONArray items = null;
        for (String feed : FEEDS) {
            try {
                JSONObject root = new JSONObject(new String(get(feed, MAX_FEED_BYTES), StandardCharsets.UTF_8));
                items = root.optJSONArray("wallpapers");
                if (items != null && items.length() > 0) {
                    Log.i(TAG, "auto update: " + items.length() + " entries from " + feed);
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "auto update: feed " + feed + " failed: " + e.getMessage());
            }
            items = null;
        }
        if (items == null) throw new IOException("no feed reachable");
        List<String> ids = new ArrayList<>();
        Map<String, String> urls = new LinkedHashMap<>(), captions = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", ""), url = o.optString("url", "");
            if (id.isEmpty() || !url.startsWith("https://")) continue;
            ids.add(id);
            String name = WallpaperFeedPlan.fileName(id);
            if (name != null && !urls.containsKey(name)) {
                urls.put(name, url);
                captions.put(name, o.optString("caption", ""));
            }
        }
        Map<String, String> wanted = WallpaperFeedPlan.wanted(ids, WallpaperFeedPlan.KEEP);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        int changed = 0, failed = 0;
        for (String name : wanted.keySet()) {
            File target = new File(folder, name);
            if (target.isFile() && target.length() > 0) continue;
            try {
                save(get(urls.get(name), MAX_IMAGE_BYTES), target);
                changed++;
            } catch (Exception e) {
                failed++;
                Log.w(TAG, "auto update: skip " + name + ": " + e.getMessage());
            }
        }
        // Only delete old auto files once the new set is at least partly on disk.
        List<String> existing = new ArrayList<>();
        String[] list = folder.list();
        if (list != null) Collections.addAll(existing, list);
        boolean anyWanted = false;
        for (String name : wanted.keySet()) if (new File(folder, name).isFile()) anyWanted = true;
        if (anyWanted) {
            for (String name : WallpaperFeedPlan.stale(existing, wanted.keySet())) {
                if (new File(folder, name).delete()) changed++;
            }
        }
        Map<String, String> present = new LinkedHashMap<>();
        for (String name : wanted.keySet()) {
            if (new File(folder, name).isFile()) present.put(name, captions.get(name));
        }
        writeCaptions(folder, present);
        Log.i(TAG, "auto update done: " + present.size() + " auto pictures, " + changed + " changed, " + failed + " failed");
        return changed;
    }

    private static void writeCaptions(File folder, Map<String, String> present) {
        File file = new File(folder, WallpaperCaptions.FILE_NAME);
        try {
            List<String> old = file.isFile() && file.length() <= 256 * 1024
                    ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
            List<String> merged = WallpaperFeedPlan.mergeCaptions(old, present);
            if (merged.equals(old)) return;
            File tmp = new File(folder, WallpaperCaptions.FILE_NAME + ".part");
            Files.write(tmp.toPath(), merged, StandardCharsets.UTF_8);
            if (!tmp.renameTo(file)) Log.w(TAG, "auto update: could not write captions.txt");
        } catch (Exception e) {
            Log.w(TAG, "auto update: captions.txt: " + e.getMessage());
        }
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            if (c.getContentLengthLong() > max) throw new IOException("too large");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > max) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Decodes, scales to at most 1920x1080 and writes a JPEG atomically (.part then rename). */
    private static void save(byte[] data, File target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not an image");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = WallpaperFeedPlan.sampleSize(bounds.outWidth, bounds.outHeight, 1920, 1080);
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bitmap == null) throw new IOException("decode failed");
        int[] size = WallpaperFeedPlan.fit(bitmap.getWidth(), bitmap.getHeight(), 1920, 1080);
        if (size[0] != bitmap.getWidth() || size[1] != bitmap.getHeight()) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } finally {
            bitmap.recycle();
        }
        if (part.length() <= 0 || part.length() > MAX_SAVED_BYTES || !part.renameTo(target)) {
            part.delete();
            throw new IOException("could not save");
        }
    }
}
