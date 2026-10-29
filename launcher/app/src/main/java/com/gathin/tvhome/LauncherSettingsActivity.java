package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView onlineChoice;
    private TextView onlineStatus;
    static final String FEED_JSDELIVR = "https://cdn.jsdelivr.net/gh/rvinauto/cleanyourtv@wallpaper-feed/feed.json";
    static final String FEED_GITHUB = "https://raw.githubusercontent.com/rvinauto/cleanyourtv/wallpaper-feed/feed.json";
    /** Wallpaper type: the same feed host serves feed.json (scenery), feed-movies.json and feed-mixed.json. */
    private static final String[] FEED_FILES = {"feed.json", "feed-movies.json", "feed-mixed.json"};
    private static final int[] FEED_TYPE_LABELS = {R.string.online_type_scenery, R.string.online_type_movies,
            R.string.online_type_mixed};

    /** Index into FEED_FILES for a built-in feed address, or -1 for a custom one. */
    static int feedType(String feed) {
        for (String base : new String[]{FEED_JSDELIVR, FEED_GITHUB}) {
            String dir = base.substring(0, base.length() - "feed.json".length());
            for (int i = 0; i < FEED_FILES.length; i++) if ((dir + FEED_FILES[i]).equals(feed)) return i;
        }
        return -1;
    }

    /** The built-in base address (jsDelivr or GitHub) behind a typed feed address, else the address itself. */
    static String feedBase(String feed) {
        int type = feedType(feed);
        if (type <= 0) return feed;
        return feed.substring(0, feed.length() - FEED_FILES[type].length()) + "feed.json";
    }

    static String withType(String feed, int type) {
        String base = feedBase(feed);
        return base.substring(0, base.length() - "feed.json".length()) + FEED_FILES[type];
    }

    private static final int[] REFRESH_HOURS = {1, 3, 6, 12, 24, 48, 168};
    private static final int[] CACHE_COUNTS = {3, 5, 10, 15, 20, 30};
    private static final int[] ROTATION_MINUTES = {0, 5, 15, 30, 60, 120, 360, 1440};
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        onlineChoice = findViewById(R.id.onlineWallpaperChoice);
        onlineStatus = findViewById(R.id.onlineWallpaperStatus);
        onlineChoice.setOnClickListener(v -> showOnlineMenu());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            Config.removeOnlineChoices(choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY)
                    .remove("animations").remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY)).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One row on the main screen; everything else about the online wallpaper lives in this remote-friendly list. */
    private void showOnlineMenu() {
        Config config = Config.load(this);
        OnlineWallpapers.Options o = config.onlineWallpaper;
        boolean enabled = choices.getBoolean(Config.ONLINE_KEY, o.enabled);
        int rotation = config.wallpaperRotationMinutes;
        String[] items = {
                getString(R.string.online_toggle, getString(enabled ? R.string.on : R.string.off)),
                getString(R.string.online_source, sourceLabel(o.feed)),
                getString(R.string.online_type, typeLabel(o.feed)),
                getString(R.string.online_captions, getString(config.wallpaperCaptions ? R.string.on : R.string.off)),
                getString(R.string.online_refresh_hours, (int) (o.interval / 3_600_000L)),
                getString(R.string.online_cache_count, o.count),
                getString(R.string.online_rotation, rotationLabel(rotation)),
                getString(R.string.online_refresh_now),
                getString(R.string.online_clear_cache)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_wallpaper_label)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            choices.edit().putBoolean(Config.ONLINE_KEY, !enabled).apply();
                            onlineChanged(true);
                            break;
                        case 1: showSourcePicker(o.feed); break;
                        case 2: showTypePicker(o.feed); break;
                        case 3:
                            choices.edit().putBoolean(Config.CAPTIONS_KEY, !config.wallpaperCaptions).apply();
                            onlineChanged(true);
                            break;
                        case 4:
                            pickNumber(R.string.online_refresh_hours, REFRESH_HOURS, (int) (o.interval / 3_600_000L),
                                    value -> choices.edit().putInt(Config.ONLINE_HOURS_KEY, value).apply());
                            break;
                        case 5:
                            pickNumber(R.string.online_cache_count, CACHE_COUNTS, o.count,
                                    value -> choices.edit().putInt(Config.ONLINE_COUNT_KEY, value).apply());
                            break;
                        case 6: showRotationPicker(rotation); break;
                        case 7:
                            choices.edit().putLong(Config.ONLINE_FORCE_KEY, System.currentTimeMillis()).apply();
                            Toast.makeText(this, R.string.online_refreshing, Toast.LENGTH_SHORT).show();
                            onlineChanged(false);
                            break;
                        default: clearOnlineCache(); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String typeLabel(String feed) {
        int type = feedType(feed);
        return type < 0 ? getString(R.string.online_source_custom) : getString(FEED_TYPE_LABELS[type]);
    }

    private void showTypePicker(String current) {
        int checked = feedType(current);
        if (checked < 0) {
            Toast.makeText(this, R.string.online_type_custom, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[FEED_FILES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(FEED_TYPE_LABELS[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putString(Config.ONLINE_FEED_KEY, withType(current, which)).apply();
                    onlineChanged(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String sourceLabel(String feed) {
        feed = feedBase(feed);
        if (FEED_JSDELIVR.equals(feed)) return getString(R.string.online_source_jsdelivr);
        if (FEED_GITHUB.equals(feed)) return getString(R.string.online_source_github);
        return feed.isEmpty() ? getString(R.string.off) : feed;
    }

    private String rotationLabel(int minutes) {
        return minutes == 0 ? getString(R.string.rotation_per_refresh) : getString(R.string.rotation_minutes, minutes);
    }

    private void showSourcePicker(String current) {
        String[] labels = {getString(R.string.online_source_jsdelivr), getString(R.string.online_source_github),
                getString(R.string.online_source_custom)};
        String base = feedBase(current);
        int type = Math.max(0, feedType(current));
        int checked = FEED_JSDELIVR.equals(base) ? 0 : FEED_GITHUB.equals(base) ? 1 : 2;
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 2) {
                        showCustomFeed(current);
                    } else {
                        choices.edit().putString(Config.ONLINE_FEED_KEY,
                                withType(which == 0 ? FEED_JSDELIVR : FEED_GITHUB, type))
                                .apply();
                        onlineChanged(true);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The TV's own on-screen keyboard types the address; only https is accepted, as the downloader requires. */
    private void showCustomFeed(String current) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.online_url_hint);
        input.setText(current);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_source_custom)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    try {
                        OnlineWallpapers.https(url);
                    } catch (java.io.IOException e) {
                        Toast.makeText(this, R.string.online_url_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    choices.edit().putString(Config.ONLINE_FEED_KEY, url).apply();
                    onlineChanged(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface IntChoice { void save(int value); }

    private void pickNumber(int title, int[] values, int current, IntChoice save) {
        String[] labels = new String[values.length];
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = getString(title, values[i]);
            if (values[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    save.save(values[which]);
                    onlineChanged(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRotationPicker(int current) {
        String[] labels = new String[ROTATION_MINUTES.length];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = rotationLabel(ROTATION_MINUTES[i]);
            if (ROTATION_MINUTES[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putInt(Config.ROTATION_KEY, ROTATION_MINUTES[which]).apply();
                    onlineChanged(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Deleted on the online worker's own queue, so it never races a download in progress. */
    private void clearOnlineCache() {
        File dir = new File(getFilesDir(), "online-wallpapers");
        android.content.Context app = getApplicationContext();
        OnlineWallpapers.submit(() -> {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
            app.getSharedPreferences(WallpaperPlayer.STATUS_NAME, MODE_PRIVATE).edit().clear().commit();
        });
        getSharedPreferences(WallpaperPlayer.STATUS_NAME, MODE_PRIVATE).edit().clear().apply();
        Toast.makeText(this, R.string.online_cache_cleared, Toast.LENGTH_SHORT).show();
        onlineChanged(false);
    }

    private void onlineChanged(boolean reopen) {
        changed();
        refresh();
        if (reopen) showOnlineMenu();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
        Config config = Config.load(this);
        onlineChoice.setText(getString(R.string.online_wallpaper_state,
                config.onlineWallpaper.enabled ? sourceLabel(config.onlineWallpaper.feed) : getString(R.string.off)));
        SharedPreferences status = getSharedPreferences(WallpaperPlayer.STATUS_NAME, MODE_PRIVATE);
        long refreshed = status.getLong("refreshed", 0);
        String text = getString(R.string.online_status, refreshed <= 0 ? getString(R.string.online_status_never)
                        : android.text.format.DateUtils.getRelativeTimeSpanString(refreshed).toString(),
                status.getInt("count", 0));
        String error = status.getString("error", null);
        if (error != null) text += "\n" + getString(R.string.online_status_error, error);
        onlineStatus.setText(text);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.ONLINE_KEY) || choices.contains(Config.ONLINE_FEED_KEY)
                || choices.contains(Config.ONLINE_HOURS_KEY) || choices.contains(Config.ONLINE_COUNT_KEY)
                || choices.contains(Config.ROTATION_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
