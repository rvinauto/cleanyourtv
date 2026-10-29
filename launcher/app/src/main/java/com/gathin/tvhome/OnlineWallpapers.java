package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Private, transactional cache. All methods that touch disk/network belong on a worker thread. */
final class OnlineWallpapers {
    static final int MAX_IMAGE = 10 * 1024 * 1024, MAX_FEED = 256 * 1024;

    static final class Options {
        final boolean enabled;
        final String feed;
        final long interval;
        final int count;
        /** A "refresh now" request from Launcher settings: a cache refreshed before this time is due. */
        final long forceAfter;

        Options(boolean enabled, String feed, int hours, int count) {
            this(enabled, feed, hours, count, 0);
        }

        Options(boolean enabled, String feed, int hours, int count, long forceAfter) {
            this.forceAfter = forceAfter;
            this.enabled = enabled;
            this.feed = feed.trim();
            interval = Math.max(1, Math.min(168, hours)) * 3_600_000L;
            this.count = Math.max(1, Math.min(30, count));
        }
    }

    static final class Entry {
        final String key, url, caption;
        final File file;

        Entry(String key, String url, String caption, File file) {
            this.key = key;
            this.url = url;
            this.caption = caption;
            this.file = file;
        }
    }

    static final class Snapshot {
        final List<Entry> entries;
        final long refreshed;
        final String feedKey, etag, modified;

        Snapshot(List<Entry> entries, long refreshed, String feedKey, String etag, String modified) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.refreshed = refreshed;
            this.feedKey = feedKey;
            this.etag = etag;
            this.modified = modified;
        }
    }

    /** Platform I/O is supplied once, rather than three independent adapters for the same worker. */
    interface Platform {
        Response get(String url, String etag, String modified) throws IOException;
        boolean valid(File image) throws IOException;
        void sync(File directory) throws IOException;
        /** Diagnostics only (logcat on the TV); never required for correctness. */
        default void log(String message) {}
    }

    private static final class Worker {
        // One queue across Activity recreations. The thread retires when idle; no periodic work.
        static final ThreadPoolExecutor QUEUE = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
    }

    static Future<?> submit(Runnable task) { return Worker.QUEUE.submit(task); }

    static class Response implements AutoCloseable {
        final int status;
        final InputStream body;
        private final HttpURLConnection connection;

        Response(HttpURLConnection connection) throws IOException {
            this.connection = connection;
            status = connection.getResponseCode();
            body = status >= 200 && status < 300 ? connection.getInputStream() : null;
        }

        @Override public void close() throws IOException {
            try { if (body != null) body.close(); }
            finally { connection.disconnect(); }
        }
    }

    private final File directory;
    private final Options options;
    private final Platform platform;
    private static final Snapshot EMPTY = new Snapshot(Collections.emptyList(), 0, "", "", "");

    OnlineWallpapers(File filesDir, Options options, Platform platform) {
        if (filesDir == null) throw new IllegalArgumentException();
        directory = new File(filesDir, "online-wallpapers");
        this.options = options;
        this.platform = platform;
    }

    /** Never scan the cache at all when the feature is off. A damaged state is left untouched. */
    Snapshot load() {
        if (!options.enabled) return EMPTY;
        try {
            File state = new File(directory, "state.json");
            if (state.length() > 128 * 1024) return EMPTY;
            JSONObject p = object(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8));
            JSONArray images = p.getJSONArray("cache");
            List<Entry> entries = new ArrayList<>();
            boolean complete = true;
            if (images.length() > 30) return EMPTY;
            for (int i = 0; i < images.length(); i++) {
                JSONObject item = images.getJSONObject(i);
                String name = item.getString("file");
                File file = new File(directory, name);
                if (!name.matches("image-[A-Za-z0-9-]+\\.jpg") || !file.isFile()
                        || file.length() > MAX_IMAGE || !platform.valid(file)) {
                    complete = false;
                    continue;
                }
                entries.add(new Entry(item.getString("key"), "", item.getString("caption"), file));
            }
            Snapshot loaded = new Snapshot(entries, complete ? p.getLong("refreshed") : 0,
                    p.getString("feed"), complete ? header(p.getString("etag")) : "",
                    complete ? header(p.getString("modified")) : "");
            if (complete && !entries.isEmpty()) {
                try {
                    prune(loaded);
                } catch (IOException ignored) { /* keep both generations if directory sync is unavailable */ }
            }
            return loaded;
        } catch (Exception | OutOfMemoryError | StackOverflowError failure) {
            return EMPTY;
        }
    }

    boolean due(Snapshot cached, long now) {
        return options.enabled && (!hash(options.feed).equals(cached.feedKey) || cached.entries.isEmpty()
                || cached.entries.size() > options.count || cached.refreshed <= 0
                || now < cached.refreshed || now - cached.refreshed >= options.interval
                || cached.refreshed < options.forceAfter);
    }

    /** One attempt per Home instance, no retry loop. On any failure the caller retains this exact snapshot. */
    Snapshot refresh(Snapshot cached, long now) throws Exception {
        if (!due(cached, now)) {
            platform.log("cache fresh: " + cached.entries.size() + " images");
            return cached;
        }
        https(options.feed);
        platform.log("fetching feed " + options.feed);
        if (!directory.isDirectory()) {
            if (!directory.mkdirs()) throw new IOException();
            platform.sync(directory.getParentFile()); // persist the new cache directory entry too
        }
        String feedKey = hash(options.feed);
        boolean conditional = feedKey.equals(cached.feedKey) && !cached.entries.isEmpty()
                && cached.entries.size() <= options.count;
        List<File> created = new ArrayList<>();
        boolean committed = false;
        try {
            Snapshot next;
            try (Response feed = platform.get(options.feed, conditional ? cached.etag : "",
                    conditional ? cached.modified : "")) {
                List<Entry> entries;
                platform.log("feed HTTP " + feed.status);
                if (feed.status == 304 && conditional) {
                    entries = cached.entries;
                } else {
                    success(feed);
                    ByteArrayOutputStream json = new ByteArrayOutputStream();
                    copy(feed, json, MAX_FEED);
                    List<Entry> offered = parse(json.toString(StandardCharsets.UTF_8.name()));
                    if (offered.isEmpty()) throw new IOException("feed has no entries");
                    platform.log("feed entries: " + offered.size());
                    entries = new ArrayList<>();
                    for (Entry item : offered.subList(0, Math.min(options.count, offered.size()))) {
                        File tmp = File.createTempFile("image-", ".tmp", directory);
                        created.add(tmp);
                        try (Response response = platform.get(item.url, "", "");
                             FileOutputStream out = new FileOutputStream(tmp)) {
                            if (response.status < 200 || response.status >= 300)
                                platform.log("image fail HTTP " + response.status + ": " + item.url);
                            success(response);
                            copy(response, out, MAX_IMAGE);
                            out.getFD().sync();
                        }
                        if (!platform.valid(tmp)) {
                            platform.log("image fail undecodable: " + item.url);
                            throw new IOException("undecodable image");
                        }
                        platform.log("image ok " + tmp.length() + " bytes: " + item.url);
                        File image = new File(directory, tmp.getName().replace(".tmp", ".jpg"));
                        if (image.exists()) throw new IOException(); // never replace a previous cache file
                        Files.move(tmp.toPath(), image.toPath(), StandardCopyOption.ATOMIC_MOVE);
                        created.add(image);
                        entries.add(new Entry(item.key, "", item.caption, image));
                    }
                    // New feed entries come first; retain the most recently cached older entries up to the cap.
                    Set<String> keys = new HashSet<>();
                    for (Entry entry : entries) keys.add(entry.key);
                    for (Entry entry : cached.entries) {
                        if (entries.size() == options.count) break;
                        if (keys.add(entry.key)) entries.add(entry);
                    }
                }
                String etag = header(feed.connection.getHeaderField("ETag"));
                String modified = header(feed.connection.getHeaderField("Last-Modified"));
                next = new Snapshot(entries, now, feedKey,
                        feed.status == 304 && etag.isEmpty() ? cached.etag : etag,
                        feed.status == 304 && modified.isEmpty() ? cached.modified : modified);
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException();
            platform.sync(directory); // persist validated image renames before publishing their names
            save(next);
            committed = true;
            prune(next);
            return next;
        } finally {
            if (!committed) for (File file : created) file.delete();
        }
    }

    private void save(Snapshot state) throws Exception {
        JSONArray images = new JSONArray();
        for (Entry entry : state.entries) {
            images.put(new JSONObject().put("file", entry.file.getName())
                    .put("key", entry.key).put("caption", entry.caption));
        }
        // Hash only: never persist a feed URL or its query string.
        JSONObject p = new JSONObject().put("feed", state.feedKey).put("refreshed", state.refreshed)
                .put("etag", state.etag).put("modified", state.modified).put("cache", images);
        File tmp = new File(directory, "state.tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(p.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(tmp.toPath(), new File(directory, "state.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            tmp.delete(); // best effort; never turn a successful rename into a rollback that deletes its images
        }
    }

    private void prune(Snapshot state) throws IOException {
        platform.sync(directory.getParentFile()); // also cover an earlier interrupted directory creation
        platform.sync(directory); // persist the state rename before deleting either generation's files
        Set<File> keep = new HashSet<>();
        for (Entry entry : state.entries) keep.add(entry.file);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if ((name.startsWith("image-") && (name.endsWith(".jpg") || name.endsWith(".tmp")))
                    && !keep.contains(file)) {
                // Deletion failure must not roll back an already committed, valid cache.
                file.delete();
            }
        }
    }

    private static JSONObject object(String text) throws Exception {
        JSONTokener parser = new JSONTokener(text);
        Object value;
        try {
            value = parser.nextValue();
        } catch (StackOverflowError failure) {
            throw new IOException();
        }
        if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw new IOException();
        return (JSONObject) value;
    }

    static List<Entry> parse(String text) throws Exception {
        JSONObject feed = object(text);
        if (!(feed.opt("version") instanceof Number) || feed.getDouble("version") != 1)
            throw new IOException();
        JSONArray images = feed.getJSONArray("wallpapers");
        if (images.length() > 200) throw new IOException();
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>(), urls = new HashSet<>();
        for (int i = 0; i < images.length(); i++) {
            JSONObject item = images.getJSONObject(i);
            String id = text(item, "id", 256), url = text(item, "url", 2048);
            https(url);
            String caption = item.has("caption") ? text(item, "caption", 500) : "";
            if (id.isEmpty()) throw new IOException();
            if (ids.contains(id) || urls.contains(url)) continue;
            ids.add(id);
            urls.add(url);
            entries.add(new Entry(hash(id + "\n" + url), url, caption, null));
        }
        return entries;
    }

    private static String text(JSONObject object, String key, int limit) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).length() > limit)
            throw new IOException();
        return ((String) value).trim();
    }

    static URI https(String value) throws IOException {
        try {
            URI uri = new URI(value);
            String path = uri.getPath();
            if (value.length() > 2048 || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !uri.normalize().equals(uri) || value.indexOf('\\') >= 0
                    || (path != null && (path.indexOf('\\') >= 0 || path.matches("(^|.*/)\\.\\.?(/.*|$)")))
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IOException();
            return uri;
        } catch (java.net.URISyntaxException failure) {
            throw new IOException();
        }
    }

    private static String header(String value) {
        return value == null || value.length() > 512 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? "" : value;
    }

    private static String hash(String value) {
        try {
            return new BigInteger(1, MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).toString(16);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void success(Response response) throws IOException {
        if (response.status < 200 || response.status >= 300) throw new IOException();
    }

    private static void copy(Response response, OutputStream out, int limit) throws IOException {
        long length = response.connection.getContentLengthLong();
        if (length > limit || response.body == null) throw new IOException();
        byte[] buffer = new byte[8192];
        int total = 0, size;
        long deadline = System.nanoTime() + 30_000_000_000L;
        while ((size = response.body.read(buffer)) != -1) {
            total += size;
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline || total > limit)
                throw new IOException();
            out.write(buffer, 0, size);
        }
        if (length >= 0 && total != length) throw new IOException();
    }

    static Response request(String url, String etag, String modified) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) https(url).toURL().openConnection();
        return request(connection, etag, modified);
    }

    static Response request(HttpURLConnection connection, String etag, String modified) throws IOException {
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false); // never follow a downgrade, or send validators elsewhere
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (!header(etag).isEmpty()) connection.setRequestProperty("If-None-Match", etag);
            if (!header(modified).isEmpty()) connection.setRequestProperty("If-Modified-Since", modified);
            return new Response(connection);
        } catch (IOException | RuntimeException failure) {
            connection.disconnect();
            throw failure;
        }
    }
}
