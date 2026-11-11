package com.gathin.tvhome.companion;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {

    private static final String PREFS = "companion";
    private static final String KEY_RECENTS = "recents";
    private static final int MAX_RECENTS = 6;

    private EditText host;
    private EditText port;
    private Button connect;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout recents;
    private TextView noRecent;
    private final List<Button> sectionButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        host = findViewById(R.id.host);
        port = findViewById(R.id.port);
        connect = findViewById(R.id.connect);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        recents = findViewById(R.id.recents);
        noRecent = findViewById(R.id.no_recent);

        sectionButtons.add(findViewById(R.id.apps));
        sectionButtons.add(findViewById(R.id.optimize));
        sectionButtons.add(findViewById(R.id.install));
        sectionButtons.add(findViewById(R.id.screenshot));

        connect.setOnClickListener(v -> connect());
        findViewById(R.id.apps).setOnClickListener(v -> open(AppsActivity.class));
        findViewById(R.id.optimize).setOnClickListener(v -> open(OptimizeActivity.class));
        findViewById(R.id.install).setOnClickListener(v -> open(InstallActivity.class));
        findViewById(R.id.screenshot).setOnClickListener(v -> open(ScreenshotActivity.class));

        renderRecents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AdbSession session = CompanionApp.get().session();
        boolean connected = session != null;
        for (Button button : sectionButtons) button.setEnabled(connected);
        if (connected) {
            status.setText(getString(R.string.connected, session.deviceLabel()));
            connect.setText(R.string.connect);
            connect.setEnabled(true);
            progress.setVisibility(View.GONE);
        }
    }

    private void open(Class<?> activity) {
        if (CompanionApp.get().session() == null) {
            CompanionApp.toast(getString(R.string.status_not_connected));
            return;
        }
        startActivity(new Intent(this, activity));
    }

    private void connect() {
        String address = host.getText().toString().trim();
        if (!isPlausibleHost(address)) {
            host.setError(getString(R.string.host_invalid));
            return;
        }
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            port.setError(getString(R.string.port_invalid));
            return;
        }
        if (portNumber < 1 || portNumber > 65535) {
            port.setError(getString(R.string.port_invalid));
            return;
        }

        host.setError(null);
        port.setError(null);
        connect.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.connecting);
        for (Button button : sectionButtons) button.setEnabled(false);

        String finalAddress = address;
        int finalPort = portNumber;
        CompanionApp.get().io().execute(() -> {
            try {
                AdbSession session = AdbSession.connect(getApplicationContext(), finalAddress, finalPort);
                CompanionApp.get().setSession(session);
                remember(finalAddress, finalPort);
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    connect.setEnabled(true);
                    status.setText(getString(R.string.connected, session.deviceLabel()));
                    for (Button button : sectionButtons) button.setEnabled(true);
                    renderRecents();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    connect.setEnabled(true);
                    status.setText(getString(R.string.connect_failed, message));
                    for (Button button : sectionButtons) {
                        button.setEnabled(CompanionApp.get().session() != null);
                    }
                });
            }
        });
    }

    /** A LAN address or hostname, never a URL and never something with a space in it. */
    private static boolean isPlausibleHost(String value) {
        if (value.isEmpty() || value.length() > 253) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\' || c == '@') return false;
        }
        return true;
    }

    private void remember(String address, int portNumber) {
        String entry = address + ":" + portNumber;
        List<String> all = readRecents();
        all.remove(entry);
        all.add(0, entry);
        while (all.size() > MAX_RECENTS) all.remove(all.size() - 1);
        prefs().edit().putString(KEY_RECENTS, String.join("\n", all)).apply();
    }

    private List<String> readRecents() {
        String raw = prefs().getString(KEY_RECENTS, "");
        List<String> all = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return all;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) all.add(line.trim());
        }
        return all;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void renderRecents() {
        recents.removeAllViews();
        List<String> all = readRecents();
        noRecent.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        for (String entry : all) {
            Button button = new Button(this);
            button.setText(entry);
            button.setOnClickListener(v -> {
                int split = entry.lastIndexOf(':');
                if (split <= 0) return;
                host.setText(entry.substring(0, split));
                port.setText(entry.substring(split + 1));
                connect();
            });
            recents.addView(button);
        }
    }
}
