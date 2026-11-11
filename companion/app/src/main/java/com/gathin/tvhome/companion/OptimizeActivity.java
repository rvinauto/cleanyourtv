package com.gathin.tvhome.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class OptimizeActivity extends Activity {

    private static final String BACKUP = "disabled-backup.txt";

    private EditText packages;
    private Button apply;
    private Button restore;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_optimize);

        packages = findViewById(R.id.packages);
        apply = findViewById(R.id.apply);
        restore = findViewById(R.id.restore);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);

        apply.setOnClickListener(v -> confirmApply());
        restore.setOnClickListener(v -> restoreBackup());
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

    private File backupFile() {
        return new File(getFilesDir(), BACKUP);
    }

    private void confirmApply() {
        List<String> wanted = Commands.parsePackageList(packages.getText().toString());
        if (wanted.isEmpty()) {
            CompanionApp.toast(getString(R.string.optimize_no_packages));
            return;
        }
        List<String> allowed = new ArrayList<>();
        int skippedCore = 0;
        for (String pkg : wanted) {
            if (CorePackages.isCore(pkg)) {
                skippedCore++;
            } else {
                allowed.add(pkg);
            }
        }
        if (allowed.isEmpty()) {
            CompanionApp.toast(getString(R.string.optimize_no_packages));
            return;
        }
        final int skipped = skippedCore;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.optimize_confirm_title, allowed.size()))
                .setMessage(R.string.optimize_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.optimize_apply, (dialog, which) -> apply(allowed, skipped))
                .show();
    }

    private void apply(List<String> allowed, int skippedCore) {
        final AdbSession session = session();
        if (session == null || busy) return;
        busy = true;
        setWorking(true, getString(R.string.optimize_working));

        CompanionApp.get().io().execute(() -> {
            try {
                String disabledNow = session.shell(Commands.listDisabled());
                writeBackup(Commands.parsePackages(disabledNow));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                CompanionApp.get().post(() -> {
                    setWorking(false, getString(R.string.optimize_backup_failed, message));
                    busy = false;
                });
                return;
            }

            int done = 0;
            int failed = 0;
            for (int i = 0; i < allowed.size(); i++) {
                String pkg = allowed.get(i);
                final int index = i + 1;
                CompanionApp.get().post(() ->
                        status.setText(getString(R.string.optimize_running, index, allowed.size())));
                try {
                    session.shell(Commands.disable(pkg));
                    done++;
                } catch (Exception e) {
                    failed++;
                }
            }
            final int doneCount = done;
            final int failedCount = failed;
            CompanionApp.get().post(() -> {
                setWorking(false, getString(R.string.optimize_done, doneCount, skippedCore, failedCount));
                busy = false;
            });
        });
    }

    private void restoreBackup() {
        final AdbSession session = session();
        if (session == null || busy) return;
        final List<String> saved = readBackup();
        if (saved.isEmpty()) {
            CompanionApp.toast(getString(R.string.optimize_nothing_to_restore));
            return;
        }
        busy = true;
        setWorking(true, getString(R.string.optimize_working));
        CompanionApp.get().io().execute(() -> {
            int done = 0;
            for (String pkg : saved) {
                try {
                    session.shell(Commands.enable(pkg));
                    done++;
                } catch (Exception ignored) {
                    // Keep going: one package that vanished should not block the rest.
                }
            }
            final int restored = done;
            CompanionApp.get().post(() -> {
                setWorking(false, getString(R.string.optimize_restored, restored));
                busy = false;
            });
        });
    }

    private void setWorking(boolean working, String message) {
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(message);
        apply.setEnabled(!working);
        restore.setEnabled(!working);
    }

    private void writeBackup(java.util.Set<String> disabled) throws IOException {
        File target = backupFile();
        File tmp = new File(getFilesDir(), BACKUP + ".tmp");
        StringBuilder text = new StringBuilder();
        for (String pkg : disabled) text.append(pkg).append('\n');
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not write " + target.getName());
        }
    }

    private List<String> readBackup() {
        File file = backupFile();
        List<String> saved = new ArrayList<>();
        if (!file.isFile()) return saved;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            saved.addAll(Commands.parsePackageList(text));
        } catch (IOException ignored) {
            return new ArrayList<>();
        }
        return saved;
    }
}
