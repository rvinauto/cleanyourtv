package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final List<File> localFiles;
    private final Map<String, String> localNames;
    private final boolean online;
    private Future<?> onlineTask;
    private volatile OnlineWallpapers.Snapshot pendingOnline;
    private OnlineWallpapers.Snapshot appliedOnline;
    private boolean onlineLoading;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        localFiles = new ArrayList<>(files);
        online = config.onlineWallpaper.enabled;
        onlineLoading = online;
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files) + (!online ? ""
                : ":online:" + Integer.toHexString(config.onlineWallpaper.feed.hashCode()));
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        localNames = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        names = new HashMap<>(localNames);
        caption = names.isEmpty() && !(online && config.wallpaperCaptions)
                ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && (!files.isEmpty() || online)) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
        if (online) onlineTask = Online.start(context.getApplicationContext(), config.onlineWallpaper, this);
    }

    /** Only the application and a weak target cross the worker boundary; no view or Activity is retained. */
    private static final class Online implements OnlineWallpapers.Platform {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final Online PLATFORM = new Online();

        static Future<?> start(Context app, OnlineWallpapers.Options options, WallpaperPlayer player) {
            WeakReference<WallpaperPlayer> target = new WeakReference<>(player);
            return OnlineWallpapers.submit(() -> {
                try {
                    OnlineWallpapers cache = new OnlineWallpapers(app.getFilesDir(), options, PLATFORM);
                    OnlineWallpapers.Snapshot cached = cache.load();
                    post(target, cached, true);
                    post(target, cache.refresh(cached, System.currentTimeMillis()), false);
                } catch (Exception | OutOfMemoryError | StackOverflowError failure) {
                    post(target, null, true);
                }
            });
        }

        private static void post(WeakReference<WallpaperPlayer> target, OnlineWallpapers.Snapshot snapshot, boolean initial) {
            MAIN.post(() -> {
                WallpaperPlayer player = target.get();
                if (player != null && !player.closed) player.receiveOnline(snapshot, initial);
            });
        }

        @Override public OnlineWallpapers.Response get(String url, String etag, String modified) throws IOException {
            return OnlineWallpapers.request(url, etag, modified);
        }

        @Override public boolean valid(File image) { return validOnlineImage(image); }

        @Override public void sync(File directory) throws IOException {
            try {
                FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
                try { Os.fsync(fd); } finally { Os.close(fd); }
            } catch (android.system.ErrnoException failure) {
                throw new IOException(failure);
            }
        }
    }

    private void receiveOnline(OnlineWallpapers.Snapshot snapshot, boolean initial) {
        if (snapshot != null) pendingOnline = snapshot;
        if (!initial) return; // newly downloaded files wait for the ordinary rotation clock
        if (snapshot != null) {
            admitOnline();
            if (!snapshot.entries.isEmpty() && WallpaperRotation.find(files,
                    preferences.getString("image", "")) == null) {
                preferences.edit().putString("image", snapshot.entries.get(0).file.getName()).apply();
            }
        }
        onlineLoading = false;
        if (preferences.getLong("changed", 0) == 0) {
            preferences.edit().putLong("changed", System.currentTimeMillis()).apply();
        }
        update();
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!online && !displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
        if (onlineTask != null) onlineTask.cancel(true);
    }

    void update() {
        if (!active || closed || loading || onlineLoading) return;
        String previous = preferences.getString("image", "");
        long changed = preferences.getLong("changed", 0);
        if (pendingOnline != appliedOnline && WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            admitOnline();
        }
        if (files.isEmpty()) return;
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null) return;
        if (selected.getName().equals(displayed)) {
            // The synchronous local fallback may already show this image before the cache has loaded.
            if (online && (!displayed.equals(previous) || changed == 0)) {
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
            }
            return;
        }
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /** No download callback changes the displayed bitmap, the rotation clock or its remembered position. */
    private void admitOnline() {
        if (pendingOnline == null || pendingOnline == appliedOnline) return;
        appliedOnline = pendingOnline;
        files.clear();
        files.addAll(WallpaperRotation.withOnline(localFiles, appliedOnline.entries));
        names.clear();
        names.putAll(localNames);
        for (OnlineWallpapers.Entry entry : appliedOnline.entries) {
            if (!entry.caption.isEmpty()) names.put(entry.file.getName(), entry.caption);
        }
    }

    /** Bounds first, then an actual sampled decode; a header alone is not a valid cached image. */
    private static boolean validOnlineImage(File file) {
        Bitmap bitmap = decode(file, 256, 256, Bitmap.Config.RGB_565, 0, true);
        if (bitmap == null) return false;
        bitmap.recycle();
        return true;
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    private Bitmap decode(File file) {
        return decode(file, width, height, bitmapConfig, dimColor, false);
    }

    /** Preview validates raw dimensions before decoding; both paths share bounds, sampling and failure handling. */
    private static Bitmap decode(File file, int screenW, int screenH, Bitmap.Config color, int dim, boolean preview) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            if (preview && !WallpaperSize.accepted(width, height)) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = color;
            options.inMutable = dim != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (preview) return decoded;
            if (dim != 0) new Canvas(decoded).drawColor(dim);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
