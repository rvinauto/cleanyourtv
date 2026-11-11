package com.gathin.tvhome.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class InstallActivity extends Activity {

    private static final int PICK_APK = 41;

    private Button pick;
    private Button pickLauncher;
    private Button install;
    private TextView file;
    private TextView status;
    private ProgressBar progress;

    private Uri selected;
    private String selectedName;
    private long selectedSize;
    private boolean launcherMode;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);

        pick = findViewById(R.id.pick);
        pickLauncher = findViewById(R.id.pick_launcher);
        install = findViewById(R.id.install);
        file = findViewById(R.id.file);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);

        pick.setOnClickListener(v -> choose(false));
        pickLauncher.setOnClickListener(v -> choose(true));
        install.setOnClickListener(v -> install());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CompanionApp.get().session() == null) {
            CompanionApp.toast(getString(R.string.status_not_connected));
            finish();
        }
    }

    private AdbSession session() {
        return CompanionApp.get().session();
    }

    private void choose(boolean forLauncher) {
        launcherMode = forLauncher;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Some file providers report octet-stream for an APK, so accept anything and check the name.
        intent.setType("*/*");
        startActivityForResult(intent, PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Not every provider grants a persistable permission; the read grant is enough for this session.
        }
        String name = null;
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the last path segment below.
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
            CompanionApp.toast(getString(R.string.install_not_apk));
            return;
        }
        selected = uri;
        selectedName = name;
        selectedSize = size;
        file.setText(getString(R.string.install_selected, name, humanSize(size)));
        install.setEnabled(!busy);
        status.setVisibility(View.GONE);
    }

    private void install() {
        final AdbSession session = session();
        if (session == null || busy) return;
        if (selected == null) {
            CompanionApp.toast(getString(R.string.install_no_file));
            return;
        }
        busy = true;
        install.setEnabled(false);
        pick.setEnabled(false);
        pickLauncher.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.install_pushing);

        final Uri uri = selected;
        final boolean forLauncher = launcherMode;
        CompanionApp.get().io().execute(() -> {
            File local = new File(getCacheDir(), "install.apk");
            try {
                copyToCache(uri, local);
                session.push(local, Commands.REMOTE_APK);
                CompanionApp.get().post(() -> status.setText(R.string.install_installing));
                String output = session.shellWithOutput(Commands.install(Commands.REMOTE_APK));
                try {
                    session.shell(Commands.remove(Commands.REMOTE_APK));
                } catch (Exception ignored) {
                    // The file is in /data/local/tmp; leaving it is untidy, not a failure.
                }
                if (!output.contains("Success")) {
                    throw new IOException(output.trim());
                }
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText(R.string.install_done);
                    resetPicker();
                    if (forLauncher) checkLauncherInstalled(session);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.install_failed, message));
                    resetPicker();
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                local.delete();
            }
        });
    }

    private void resetPicker() {
        busy = false;
        install.setEnabled(selected != null);
        pick.setEnabled(true);
        pickLauncher.setEnabled(true);
    }

    private void checkLauncherInstalled(AdbSession session) {
        CompanionApp.get().io().execute(() -> {
            boolean present;
            try {
                present = session.shell("pm list packages com.gathin.tvhome").contains("package:com.gathin.tvhome");
            } catch (Exception e) {
                present = false;
            }
            final boolean found = present;
            CompanionApp.get().post(() -> new AlertDialog.Builder(InstallActivity.this)
                    .setTitle(R.string.install_done)
                    .setMessage(found ? R.string.install_launcher_hint : R.string.install_launcher_missing)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        });
    }

    private void copyToCache(Uri uri, File target) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("cannot open the selected file");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
