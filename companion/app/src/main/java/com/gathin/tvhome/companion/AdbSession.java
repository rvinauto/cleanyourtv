package com.gathin.tvhome.companion;

import android.content.Context;

import java.io.File;
import java.io.IOException;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * One wireless-ADB connection to one TV. All calls are serialised: the app talks to a TV one command at a
 * time, which is also how the tools in {@code tools/} work.
 *
 * <p>The RSA key lives in the app's private directory. The first time the TV sees it, Android shows
 * "Allow USB debugging?" on the TV; that prompt is the TV's, not this app's, and the user must accept it
 * with the TV remote.
 */
public final class AdbSession implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    /** Long enough for someone to find the TV remote and accept the key prompt, and for a pm install. */
    private static final int SOCKET_TIMEOUT_MS = 180_000;

    private final Dadb dadb;
    private final String host;
    private final int port;

    private AdbSession(Dadb dadb, String host, int port) {
        this.dadb = dadb;
        this.host = host;
        this.port = port;
    }

    public static AdbSession connect(Context context, String host, int port) throws IOException {
        File dir = new File(context.getFilesDir(), "adb");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create the key directory");
        }
        File privateKey = new File(dir, "adbkey");
        File publicKey = new File(dir, "adbkey.pub");
        if (!privateKey.isFile() || !publicKey.isFile()) {
            // A half-written pair (app killed mid-generate) is replaced, not reused.
            //noinspection ResultOfMethodCallIgnored
            privateKey.delete();
            //noinspection ResultOfMethodCallIgnored
            publicKey.delete();
            AdbKeyPair.generate(privateKey, publicKey);
        }
        AdbKeyPair keys = AdbKeyPair.read(privateKey, publicKey);
        Dadb dadb = Dadb.create(host, port, keys, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS, false);
        AdbSession session = new AdbSession(dadb, host, port);
        try {
            session.shell("echo ready");
        } catch (IOException | RuntimeException e) {
            session.close();
            throw new IOException(friendly(e), e);
        }
        return session;
    }

    private static String friendly(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Runs a command and returns stdout; a non-zero exit is an error. */
    public synchronized String shell(String command) throws IOException {
        AdbShellResponse response = dadb.shell(command);
        String error = response.getErrorOutput() == null ? "" : response.getErrorOutput().trim();
        if (response.getExitCode() != 0) {
            throw new IOException(error.isEmpty() ? "exit " + response.getExitCode() : error);
        }
        return response.getOutput() == null ? "" : response.getOutput();
    }

    /** Runs a command and returns stdout and stderr together, whatever the exit code: for pm install. */
    public synchronized String shellWithOutput(String command) throws IOException {
        return dadb.shell(command).getAllOutput();
    }

    public synchronized void push(File local, String remote) throws IOException {
        dadb.push(local, remote, 0644, System.currentTimeMillis());
    }

    public synchronized void pull(File local, String remote) throws IOException {
        dadb.pull(local, remote);
    }

    public synchronized String deviceLabel() {
        try {
            String model = shell("getprop ro.product.model").trim();
            String release = shell("getprop ro.build.version.release").trim();
            if (model.isEmpty()) return host + ":" + port;
            return release.isEmpty() ? model : model + " · Android " + release;
        } catch (IOException | RuntimeException e) {
            return host + ":" + port;
        }
    }

    @Override
    public synchronized void close() {
        try {
            dadb.close();
        } catch (Exception ignored) {
            // Closing a socket that the TV already dropped is not worth reporting.
        }
    }
}
