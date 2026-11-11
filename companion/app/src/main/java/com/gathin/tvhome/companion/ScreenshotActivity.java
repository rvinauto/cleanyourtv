package com.gathin.tvhome.companion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

public final class ScreenshotActivity extends Activity {

    private Button take;
    private ImageView image;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screenshot);

        take = findViewById(R.id.take);
        image = findViewById(R.id.image);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);

        take.setOnClickListener(v -> takeScreenshot());
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

    private void takeScreenshot() {
        final AdbSession session = session();
        if (session == null || busy) return;
        busy = true;
        take.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.screenshot_taking);

        CompanionApp.get().io().execute(() -> {
            File local = new File(getCacheDir(), "tv.png");
            try {
                session.shell(Commands.screencap(Commands.REMOTE_SHOT));
                session.pull(local, Commands.REMOTE_SHOT);
                try {
                    session.shell(Commands.remove(Commands.REMOTE_SHOT));
                } catch (Exception ignored) {
                    // Cleanup only.
                }
                Bitmap bitmap = BitmapFactory.decodeFile(local.getAbsolutePath());
                if (bitmap == null) throw new IllegalStateException("the TV returned an empty PNG");
                CompanionApp.get().post(() -> {
                    image.setImageBitmap(bitmap);
                    progress.setVisibility(View.GONE);
                    status.setText(R.string.screenshot_title);
                    busy = false;
                    take.setEnabled(true);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.screenshot_failed, message));
                    busy = false;
                    take.setEnabled(true);
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                local.delete();
            }
        });
    }
}
