package com.gathin.tvhome.companion;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CompanionApp extends Application {

    private static CompanionApp instance;

    /** One worker: ADB is a single connection, so commands queue instead of interleaving. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private AdbSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static CompanionApp get() {
        return instance;
    }

    public ExecutorService io() {
        return io;
    }

    public void post(Runnable runnable) {
        main.post(runnable);
    }

    public synchronized AdbSession session() {
        return session;
    }

    public synchronized void setSession(AdbSession next) {
        if (session != null) session.close();
        session = next;
    }

    public synchronized void clearSession() {
        if (session != null) session.close();
        session = null;
    }

    public static void toast(String message) {
        CompanionApp app = instance;
        if (app == null) return;
        app.post(() -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
    }

    public static void toastLong(String message) {
        CompanionApp app = instance;
        if (app == null) return;
        app.post(() -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
    }
}
