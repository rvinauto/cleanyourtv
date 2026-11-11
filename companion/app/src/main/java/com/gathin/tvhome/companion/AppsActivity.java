package com.gathin.tvhome.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class AppsActivity extends Activity {

    private static final class AppEntry {
        final String pkg;
        final boolean system;
        boolean disabled;
        final boolean core;

        AppEntry(String pkg, boolean system, boolean disabled) {
            this.pkg = pkg;
            this.system = system;
            this.disabled = disabled;
            this.core = CorePackages.isCore(pkg);
        }
    }

    private final List<AppEntry> entries = new ArrayList<>();
    private ListView list;
    private TextView status;
    private ProgressBar progress;
    private Button refresh;
    private Adapter adapter;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);

        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        refresh = findViewById(R.id.refresh);

        adapter = new Adapter();
        list.setAdapter(adapter);
        refresh.setOnClickListener(v -> load());
        load();
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

    private void load() {
        final AdbSession session = session();
        if (session == null) {
            CompanionApp.toast(getString(R.string.status_not_connected));
            finish();
            return;
        }
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.apps_loading);
        refresh.setEnabled(false);

        CompanionApp.get().io().execute(() -> {
            try {
                Set<String> third = Commands.parsePackages(session.shell(Commands.listThirdParty()));
                Set<String> system = Commands.parsePackages(session.shell(Commands.listSystem()));
                Set<String> disabled = Commands.parsePackages(session.shell(Commands.listDisabled()));

                List<AppEntry> loaded = new ArrayList<>();
                for (String pkg : third) {
                    loaded.add(new AppEntry(pkg, false, disabled.contains(pkg)));
                }
                for (String pkg : system) {
                    if (!third.contains(pkg)) loaded.add(new AppEntry(pkg, true, disabled.contains(pkg)));
                }
                loaded.sort(Comparator
                        .comparingInt((AppEntry e) -> e.system ? 1 : 0)
                        .thenComparing(e -> e.pkg));

                CompanionApp.get().post(() -> {
                    entries.clear();
                    entries.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    progress.setVisibility(View.GONE);
                    refresh.setEnabled(true);
                    status.setText(getString(R.string.apps_loaded, entries.size()));
                    if (entries.isEmpty()) status.setText(R.string.apps_empty);
                    busy = false;
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    refresh.setEnabled(true);
                    status.setText(getString(R.string.apps_failed, getString(R.string.apps_refresh), message));
                    busy = false;
                });
            }
        });
    }

    private void open(AppEntry entry) {
        final AdbSession session = session();
        if (session == null) return;
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.apps_working);
        CompanionApp.get().io().execute(() -> {
            try {
                String component = Commands.parseComponent(session.shell(Commands.resolveLauncher(entry.pkg)));
                if (component == null) {
                    component = Commands.parseComponent(session.shell(Commands.resolvePhoneLauncher(entry.pkg)));
                }
                if (component == null) {
                    String message = getString(R.string.apps_no_launcher, entry.pkg);
                    CompanionApp.get().post(() -> {
                        progress.setVisibility(View.GONE);
                        status.setText(message);
                        busy = false;
                    });
                    return;
                }
                session.shell(Commands.startComponent(component));
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText(entry.pkg);
                    busy = false;
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.apps_failed, getString(R.string.apps_open), message));
                    busy = false;
                });
            }
        });
    }

    private void disable(AppEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.apps_confirm_disable_title, entry.pkg))
                .setMessage(R.string.apps_confirm_disable_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.apps_disable, (dialog, which) -> runCommand(
                        Commands.disable(entry.pkg), R.string.apps_disable_done, entry.pkg))
                .show();
    }

    private void enable(AppEntry entry) {
        runCommand(Commands.enable(entry.pkg), R.string.apps_enable_done, entry.pkg);
    }

    private void forceStop(AppEntry entry) {
        runCommand(Commands.forceStop(entry.pkg), R.string.apps_stop_done, entry.pkg);
    }

    private void runCommand(String command, int doneMessage, String pkg) {
        final AdbSession session = session();
        if (session == null) return;
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.apps_working);
        CompanionApp.get().io().execute(() -> {
            try {
                session.shell(command);
                String message = getString(doneMessage, pkg);
                CompanionApp.get().post(() -> {
                    CompanionApp.toast(message);
                    busy = false;
                    load();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                CompanionApp.get().post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.apps_failed, pkg, message));
                    busy = false;
                });
            }
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(AppsActivity.this).inflate(R.layout.row_app, parent, false);
            }
            AppEntry entry = getItem(position);
            TextView name = view.findViewById(R.id.name);
            TextView detail = view.findViewById(R.id.detail);
            Button open = view.findViewById(R.id.open);
            Button toggle = view.findViewById(R.id.toggle);
            Button stop = view.findViewById(R.id.stop);

            name.setText(entry.pkg);
            String kind = getString(entry.system ? R.string.apps_system : R.string.apps_third_party);
            if (entry.disabled) kind = kind + " · " + getString(R.string.apps_disabled);
            if (entry.core) kind = kind + " · " + getString(R.string.apps_core);
            detail.setText(kind);

            open.setEnabled(!entry.disabled);
            open.setOnClickListener(v -> open(entry));

            if (entry.core) {
                toggle.setEnabled(false);
                toggle.setText(R.string.apps_core_short);
            } else if (entry.disabled) {
                toggle.setEnabled(true);
                toggle.setText(R.string.apps_enable);
                toggle.setOnClickListener(v -> enable(entry));
            } else {
                toggle.setEnabled(true);
                toggle.setText(R.string.apps_disable);
                toggle.setOnClickListener(v -> disable(entry));
            }

            stop.setEnabled(!entry.disabled);
            stop.setOnClickListener(v -> forceStop(entry));
            return view;
        }
    }
}
