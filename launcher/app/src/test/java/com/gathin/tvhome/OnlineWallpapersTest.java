package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;


import static org.junit.Assert.*;

/** Real filesystem transactions and decodable PNGs; no TV, external network or sleeps. */
public class OnlineWallpapersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String FEED = "https://example.com/feed.json";
    private static final long NOW = 1_700_000_000_000L, HOUR = 3_600_000L;

    private interface Validator { boolean valid(File file) throws IOException; }
    private interface DirectorySync { void sync(File directory) throws IOException; }

    private static OnlineWallpapers.Response response(int status, long length, String etag, String modified, InputStream body) {
        try {
            return new OnlineWallpapers.Response(new HttpURLConnection(new URL(FEED)) {
                @Override public int getResponseCode() { return status; }
                @Override public long getContentLengthLong() { return length; }
                @Override public String getHeaderField(String name) {
                    return "ETag".equals(name) ? etag : "Last-Modified".equals(name) ? modified : null;
                }
                @Override public InputStream getInputStream() { return body; }
                @Override public void disconnect() { }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() { }
            });
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static OnlineWallpapers create(File files, OnlineWallpapers.Options options, Wire wire,
                                          Validator validator, DirectorySync sync) {
        return new OnlineWallpapers(files, options, new OnlineWallpapers.Platform() {
            @Override public OnlineWallpapers.Response get(String url, String etag, String modified) throws IOException {
                return wire.get(url, etag, modified);
            }
            @Override public boolean valid(File file) throws IOException { return validator.valid(file); }
            @Override public void sync(File directory) throws IOException { sync.sync(directory); }
        });
    }

    private static class Wire {
        final Deque<OnlineWallpapers.Response> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        String etag, modified;

        public OnlineWallpapers.Response get(String url, String etag, String modified) throws IOException {
            urls.add(url);
            if (url.endsWith("feed.json")) { this.etag = etag; this.modified = modified; }
            if (responses.isEmpty()) throw new IOException("Simulated DNS/TLS/connection error");
            return responses.remove();
        }

        void feed(String text) {
            responses.add(response(200, -1, "\"feed1\"", "Wed, 21 Oct 2015 07:28:00 GMT",
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        }

        void image(byte[] bytes) {
            responses.add(response(200, bytes.length, "", "", new ByteArrayInputStream(bytes)));
        }
    }

    private OnlineWallpapers cache(Wire wire, int count) {
        return create(temp.getRoot(), new OnlineWallpapers.Options(true, FEED, 12, count),
                wire, OnlineWallpapersTest::validImage, dir -> {});
    }

    private static String feed(String... ids) {
        StringBuilder out = new StringBuilder("{\"version\":1,\"wallpapers\":[");
        for (String id : ids) {
            if (out.charAt(out.length() - 1) != '[') out.append(',');
            out.append("{\"id\":\"").append(id).append("\",\"url\":\"https://example.com/")
                    .append(id).append(".png\",\"caption\":\"Caption ").append(id).append("\"}");
        }
        return out.append("]}").toString();
    }

    private static byte[] png() throws IOException {
        return java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private static boolean validImage(File file) throws IOException {
        // Android's compile classpath excludes java.desktop; the host JDK supplies this test decoder.
        try {
            return Class.forName("javax.imageio.ImageIO").getMethod("read", File.class).invoke(null, file) != null;
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Undecodable test image", failure);
        }
    }

    private OnlineWallpapers.Snapshot seed(OnlineWallpapers cache, Wire wire, String... ids) throws Exception {
        wire.feed(feed(ids));
        for (String ignored : ids) wire.image(png());
        return cache.refresh(cache.load(), NOW);
    }

    private void failed(OnlineWallpapers cache, OnlineWallpapers.Snapshot old, long now) throws Exception {
        File state = new File(temp.getRoot(), "online-wallpapers/state.json");
        byte[] before = state.exists() ? Files.readAllBytes(state.toPath()) : null;
        assertThrows(Exception.class, () -> cache.refresh(old, now));
        Thread.interrupted(); // a cancelled worker must not affect the test's later filesystem assertions
        if (before != null) assertArrayEquals(before, Files.readAllBytes(state.toPath()));
        for (OnlineWallpapers.Entry entry : old.entries) assertTrue(validImage(entry.file));
        assertEquals(old.entries.size(), imageCount());
        File[] leftovers = new File(temp.getRoot(), "online-wallpapers").listFiles((dir, name) -> name.endsWith(".tmp"));
        assertNotNull(leftovers);
        assertEquals(0, leftovers.length);
        assertEquals(old.entries.size(), cache.load().entries.size());
    }

    private int imageCount() {
        File[] files = new File(temp.getRoot(), "online-wallpapers").listFiles((dir, name) -> name.endsWith(".jpg"));
        return files == null ? 0 : files.length;
    }

    @Test public void disabledDoesNotReadWriteOrFetch() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = create(temp.getRoot(),
                new OnlineWallpapers.Options(false, "not a URL", 12, 10), wire, file -> {
                    fail("Must not inspect disk images"); return false;
                }, dir -> fail("Disabled must not sync or prune"));
        OnlineWallpapers.Snapshot empty = cache.load();
        assertSame(empty, cache.refresh(empty, NOW));
        assertTrue(wire.urls.isEmpty());
        assertFalse(new File(temp.getRoot(), "online-wallpapers").exists());
    }

    @Test public void unavailablePrivateStorageCannotUseARelativeCacheDirectory() {
        assertThrows(IllegalArgumentException.class, () -> create(null,
                new OnlineWallpapers.Options(true, FEED, 12, 10), new Wire(), file -> true, dir -> {}));
    }

    @Test public void validFeedIsCommittedAndReloadedWithCaptions() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot snapshot = seed(cache, wire, "001", "002");
        assertEquals(2, snapshot.entries.size());
        assertEquals("Caption 001", snapshot.entries.get(0).caption);
        assertEquals(NOW, cache.load().refreshed);
        assertEquals("\"feed1\"", cache.load().etag);
        assertEquals(2, imageCount());
        String state = new String(Files.readAllBytes(new File(temp.getRoot(),
                "online-wallpapers/state.json").toPath()), StandardCharsets.UTF_8);
        assertFalse(state.contains("https"));
        assertFalse(state.contains("example.com"));
    }

    @Test public void invalidJsonVersionAndEmptyFeedPreserveCache() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        for (String invalid : Arrays.asList("{broken", "null", feed("x") + " trailing", "[]",
                "{\"version\":2,\"wallpapers\":[]}", "{\"version\":1.5,\"wallpapers\":[]}",
                "{\"version\":\"1\",\"wallpapers\":[]}", feed(),
                "{\"version\":1,\"wallpapers\":[{\"id\":1,\"url\":\"https://example.com/a\"}]}")) {
            wire.feed(invalid);
            failed(cache, old, NOW + 12 * HOUR);
        }
    }

    @Test public void duplicatesByIdOrUrlAreDownloadedOnlyOnce() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        wire.feed("{\"version\":1,\"wallpapers\":[{\"id\":\"a\",\"url\":\"https://example.com/a\"},"
                + "{\"id\":\"a\",\"url\":\"https://example.com/b\"},"
                + "{\"id\":\"b\",\"url\":\"https://example.com/a\"}]}");
        wire.image(png());
        assertEquals(1, cache.refresh(cache.load(), NOW).entries.size());
        assertEquals(2, wire.urls.size());
    }

    @Test public void httpErrorsRedirectsAndConnectionErrorsPreserveCache() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        for (int status : new int[]{301, 302, 403, 404, 500}) {
            wire.responses.add(response(status, 0, "", "", null));
            failed(cache, old, NOW + 12 * HOUR);
        }
        failed(cache, old, NOW + 12 * HOUR); // transport throws, as DNS/TLS/socket errors do
    }

    @Test public void imageHttpFailureAndPartialBatchDoNotDestroyOldImages() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        wire.feed(feed("new1", "new2"));
        wire.image(png());
        wire.responses.add(response(500, 0, "", "", null));
        failed(cache, old, NOW + 12 * HOUR);
    }

    @Test public void oversizedImageIsRejectedWithOrWithoutContentLength() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        wire.feed(feed("huge"));
        wire.responses.add(response(200, OnlineWallpapers.MAX_IMAGE + 1L,
                "", "", new ByteArrayInputStream(png())));
        failed(cache, old, NOW + 12 * HOUR);
        wire.feed(feed("huge"));
        wire.responses.add(response(200, -1, "", "",
                new ByteArrayInputStream(new byte[OnlineWallpapers.MAX_IMAGE + 1])));
        failed(cache, old, NOW + 12 * HOUR);
    }

    @Test public void corruptOrTruncatedImageKeepsLastGoodCache() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        wire.feed(feed("bad"));
        wire.image("not a picture".getBytes(StandardCharsets.UTF_8));
        failed(cache, old, NOW + 12 * HOUR);
        wire.feed(feed("partial"));
        wire.responses.add(response(200, png().length + 10, "", "", new ByteArrayInputStream(png())));
        failed(cache, old, NOW + 12 * HOUR);
    }

    @Test public void retainsNewestAndPrunesOnlyAfterCommit() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 2);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "one", "two");
        wire.feed(feed("three")); wire.image(png());
        OnlineWallpapers.Snapshot next = cache.refresh(old, NOW + 12 * HOUR);
        assertEquals(2, imageCount());
        assertEquals("Caption three", next.entries.get(0).caption);
        assertEquals(old.entries.get(0).file, next.entries.get(1).file);
        assertFalse(old.entries.get(1).file.exists());
        assertEquals(next.entries.get(0).file, cache.load().entries.get(0).file);
    }

    @Test public void cacheCapLimitsDownloadsAndCanShrink() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 2);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "a", "b");
        OnlineWallpapers smaller = cache(wire, 1);
        wire.feed(feed("c", "d")); wire.image(png());
        assertEquals(1, smaller.refresh(old, NOW + HOUR).entries.size());
        assertEquals(1, imageCount());
        assertEquals(5, wire.urls.size()); // initial feed + 2 images; later feed + only 1 image
        assertEquals("", wire.etag); // a 304 cannot satisfy a lower cap
    }

    @Test public void refreshIntervalAndClockRollback() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "a");
        assertFalse(cache.due(old, NOW + 12 * HOUR - 1));
        assertSame(old, cache.refresh(old, NOW + 12 * HOUR - 1));
        assertEquals(2, wire.urls.size());
        assertTrue(cache.due(old, NOW + 12 * HOUR));
        assertTrue(cache.due(old, NOW - 1));
        assertEquals(HOUR, new OnlineWallpapers.Options(true, FEED, -1, 0).interval);
        assertEquals(30, new OnlineWallpapers.Options(true, FEED, 1000, 1000).count);
        assertEquals(168 * HOUR, new OnlineWallpapers.Options(true, FEED, 1000, 1).interval);
    }

    @Test public void conditional304RetainsFilesAndAdvancesSuccessTime() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "a");
        wire.responses.add(response(304, 0, "", "", null));
        OnlineWallpapers.Snapshot next = cache.refresh(old, NOW + 12 * HOUR);
        assertEquals(old.entries.get(0).file, next.entries.get(0).file);
        assertEquals(old.etag, wire.etag);
        assertEquals(old.modified, wire.modified);
        assertEquals(NOW + 12 * HOUR, cache.load().refreshed);
        assertEquals(old.etag, cache.load().etag);
        assertEquals(3, wire.urls.size());
        OnlineWallpapers.Snapshot restarted = cache.load();
        assertSame(restarted, cache.refresh(restarted, NOW + 13 * HOUR));
        assertEquals(3, wire.urls.size()); // restarting after a 304 must not check again immediately
    }

    @Test public void changedFeedDoesNotLeakValidators() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "a");
        OnlineWallpapers changed = create(temp.getRoot(),
                new OnlineWallpapers.Options(true, "https://other.example/feed.json", 12, 10), wire,
                OnlineWallpapersTest::validImage, dir -> {});
        wire.feed(feed("b")); wire.image(png());
        changed.refresh(old, NOW + HOUR);
        assertEquals("", wire.etag);
        assertEquals("", wire.modified);
    }

    @Test public void unsolicited304And304WithMissingFilesCannotEraseCache() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        wire.responses.add(response(304, 0, "", "", null));
        failed(cache, cache.load(), NOW);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "a");
        assertTrue(old.entries.get(0).file.delete());
        OnlineWallpapers.Snapshot missing = cache.load();
        assertTrue(cache.due(missing, NOW + 1));
        wire.responses.add(response(304, 0, "", "", null));
        failed(cache, missing, NOW + 1);
        assertEquals("", wire.etag);
    }

    @Test public void unsafeUrlsCannotReachTheTransport() throws Exception {
        for (String unsafe : Arrays.asList("http://example.com/a", "file:///etc/passwd", "content://a/b",
                "javascript:alert(1)", "/data/data/app/files/a", "https://user:password@example.com/a",
                "https://example.com/../config.json", "https://example.com/a#fragment", "https://example.com:0/a")) {
            assertThrows(unsafe, IOException.class, () -> OnlineWallpapers.https(unsafe));
            String text = "{\"version\":1,\"wallpapers\":[{\"id\":\"a\",\"url\":\"" + unsafe + "\"}]}";
            assertThrows(Exception.class, () -> OnlineWallpapers.parse(text));
        }
        Wire wire = new Wire();
        OnlineWallpapers invalid = create(temp.getRoot(),
                new OnlineWallpapers.Options(true, "http://example.com/feed.json", 12, 10), wire, f -> true, dir -> {});
        assertThrows(IOException.class, () -> invalid.refresh(invalid.load(), NOW));
        assertTrue(wire.urls.isEmpty());
    }

    @Test public void remoteIdsAndPathsNeverBecomeLocalFilenames() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        wire.feed("{\"version\":1,\"config\":{\"tile_menu\":false},\"wallpapers\":[{\"id\":\"../../config.json\","
                + "\"url\":\"https://example.com/wallpaper.jpg?name=../../config.json\",\"caption\":\"A caption\"}]}");
        wire.image(png());
        File image = cache.refresh(cache.load(), NOW).entries.get(0).file;
        assertEquals(new File(temp.getRoot(), "online-wallpapers").getCanonicalFile(), image.getParentFile().getCanonicalFile());
        assertTrue(image.getName().matches("image-[A-Za-z0-9-]+\\.jpg"));
        assertFalse(new File(temp.getRoot(), "config.json").exists());
    }

    @Test public void damagedStateCannotReadPathsOutsidePrivateCache() throws Exception {
        File dir = temp.newFolder("online-wallpapers");
        File outside = temp.newFile("outside.jpg");
        Files.write(outside.toPath(), png());
        Files.write(new File(dir, "state.json").toPath(), ("{\"cache\":[{\"file\":\"../outside.jpg\","
                + "\"key\":\"outside\",\"caption\":\"\"}],\"refreshed\":0,\"feed\":\"\",\"etag\":\"\",\"modified\":\"\"}")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(cache(new Wire(), 10).load().entries.isEmpty());
        assertTrue(outside.exists());
    }

    @Test public void diskFailureLeavesPreviousStateAndImagesUsable() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        assertTrue(new File(temp.getRoot(), "online-wallpapers/state.tmp").mkdir());
        wire.feed(feed("new")); wire.image(png());
        failed(cache, old, NOW + 12 * HOUR);
    }

    @Test public void orphanedFilesAfterInterruptedProcessAreCollectedWithoutNetwork() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        File orphan = new File(temp.getRoot(), "online-wallpapers/image-orphan.jpg");
        Files.write(orphan.toPath(), png());
        File unfinished = new File(temp.getRoot(), "online-wallpapers/image-orphan.tmp");
        Files.write(unfinished.toPath(), new byte[]{1});
        assertEquals(old.entries.get(0).file, cache.load().entries.get(0).file);
        assertFalse(orphan.exists());
        assertFalse(unfinished.exists());
        assertEquals(2, wire.urls.size());
    }

    @Test public void failedClosingTheFeedCannotCommitOrPruneOldCache() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 1);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        wire.responses.add(response(200, -1, "", "",
                new ByteArrayInputStream(feed("new").getBytes(StandardCharsets.UTF_8)) {
                    @Override public void close() throws IOException { throw new IOException("Connection failed at close"); }
                }));
        wire.image(png());
        failed(cache, old, NOW + 12 * HOUR);
    }

    @Test public void oversizedFeedAndEntryLimitFailBeforeDownloadingImages() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        wire.responses.add(response(200, -1, "", "",
                new ByteArrayInputStream(new byte[OnlineWallpapers.MAX_FEED + 1])));
        failed(cache, old, NOW + 12 * HOUR);
        String[] many = new String[201];
        Arrays.fill(many, "same");
        wire.feed(feed(many));
        failed(cache, old, NOW + 12 * HOUR);
    }

    @Test public void interruptionNeverCommitsNewImages() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        wire.feed(feed("new")); wire.image(png());
        OnlineWallpapers cancelled = create(temp.getRoot(),
                new OnlineWallpapers.Options(true, FEED, 12, 10), wire, file -> {
                    boolean valid = validImage(file);
                    if (file.getName().endsWith(".tmp")) Thread.currentThread().interrupt();
                    return valid;
                }, dir -> {});
        try { failed(cancelled, old, NOW + 12 * HOUR); }
        finally { Thread.interrupted(); }
    }

    @Test public void imageAndStateRenamesAreSyncedBeforeOldImagesArePruned() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers base = cache(wire, 1);
        OnlineWallpapers.Snapshot old = seed(base, wire, "old");
        File state = new File(temp.getRoot(), "online-wallpapers/state.json");
        byte[] before = Files.readAllBytes(state.toPath());
        AtomicInteger syncs = new AtomicInteger();
        OnlineWallpapers durable = create(temp.getRoot(),
                new OnlineWallpapers.Options(true, FEED, 12, 1), wire, OnlineWallpapersTest::validImage, dir -> {
                    int phase = syncs.incrementAndGet();
                    assertTrue(old.entries.get(0).file.exists());
                    assertEquals(2, imageCount());
                    if (phase == 1) assertArrayEquals(before, Files.readAllBytes(state.toPath()));
                    else assertFalse(Arrays.equals(before, Files.readAllBytes(state.toPath())));
                });
        wire.feed(feed("new")); wire.image(png());
        OnlineWallpapers.Snapshot next = durable.refresh(old, NOW + 12 * HOUR);
        assertEquals(3, syncs.get());
        assertFalse(old.entries.get(0).file.exists());
        assertTrue(validImage(next.entries.get(0).file));
    }

    @Test public void failedImageDirectorySyncCannotReplaceOldState() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers base = cache(wire, 1);
        OnlineWallpapers.Snapshot old = seed(base, wire, "old");
        OnlineWallpapers failing = create(temp.getRoot(),
                new OnlineWallpapers.Options(true, FEED, 12, 1), wire, OnlineWallpapersTest::validImage,
                dir -> { throw new IOException("Directory sync unavailable"); });
        wire.feed(feed("new")); wire.image(png());
        failed(failing, old, NOW + 12 * HOUR);
    }

    @Test public void failedStateDirectorySyncPreservesBothPossiblePowerLossGenerations() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers base = cache(wire, 1);
        OnlineWallpapers.Snapshot old = seed(base, wire, "old");
        File state = new File(temp.getRoot(), "online-wallpapers/state.json");
        byte[] before = Files.readAllBytes(state.toPath());
        AtomicInteger syncs = new AtomicInteger();
        OnlineWallpapers failing = create(temp.getRoot(),
                new OnlineWallpapers.Options(true, FEED, 12, 1), wire, OnlineWallpapersTest::validImage, dir -> {
                    if (syncs.incrementAndGet() == 2) throw new IOException("State rename not durable");
                });
        wire.feed(feed("new")); wire.image(png());
        assertThrows(IOException.class, () -> failing.refresh(old, NOW + 12 * HOUR));
        assertTrue(old.entries.get(0).file.exists());
        assertEquals(2, imageCount());
        org.json.JSONObject next = new org.json.JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8));
        File newImage = new File(state.getParentFile(), next.getJSONArray("cache").getJSONObject(0).getString("file"));
        assertTrue(validImage(newImage)); // if the new rename survives, its complete images are present
        Files.write(state.toPath(), before); // simulate the old directory entry surviving a power loss
        assertEquals(old.entries.get(0).file, base.load().entries.get(0).file);
        assertEquals(1, imageCount());
    }

    @Test public void failedDirectorySyncOnLoadKeepsCacheAndDefersOrphanPruning() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers.Snapshot old = seed(cache(wire, 1), wire, "old");
        File orphan = new File(temp.getRoot(), "online-wallpapers/image-orphan.jpg");
        Files.write(orphan.toPath(), png());
        OnlineWallpapers failing = create(temp.getRoot(),
                new OnlineWallpapers.Options(true, FEED, 12, 1), wire, OnlineWallpapersTest::validImage,
                dir -> { throw new IOException("Directory sync unavailable"); });
        assertEquals(old.entries.get(0).file, failing.load().entries.get(0).file);
        assertTrue(orphan.exists());
    }

    @Test public void sharedQueueSerializesRefreshesAcrossCacheInstances() throws Exception {
        Wire firstWire = new Wire(), secondWire = new Wire();
        firstWire.feed(feed("one")); firstWire.image(png());
        secondWire.feed(feed("two")); secondWire.image(png());
        OnlineWallpapers first = cache(firstWire, 1), second = cache(secondWire, 1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
        Future<?> a = OnlineWallpapers.submit(() -> {
            max.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
                first.refresh(first.load(), NOW);
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { active.decrementAndGet(); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> b = OnlineWallpapers.submit(() -> {
                max.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { second.refresh(second.load(), NOW + 12 * HOUR); }
                catch (Exception failure) { throw new AssertionError(failure); }
                finally { active.decrementAndGet(); }
            });
            assertThrows(TimeoutException.class, () -> b.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            a.get(3, TimeUnit.SECONDS);
            b.get(3, TimeUnit.SECONDS);
            assertEquals(1, max.get());
            assertEquals("Caption two", second.load().entries.get(0).caption);
        } finally { release.countDown(); a.cancel(true); }
    }

    @Test public void newCacheDirectoryIsDurableBeforeItsFirstStateCommit() throws Exception {
        Wire wire = new Wire();
        AtomicInteger syncs = new AtomicInteger();
        File state = new File(temp.getRoot(), "online-wallpapers/state.json");
        OnlineWallpapers cache = create(temp.getRoot(), new OnlineWallpapers.Options(true, FEED, 12, 1),
                wire, OnlineWallpapersTest::validImage, dir -> {
                    int phase = syncs.incrementAndGet();
                    if (phase == 1 || phase == 3) assertEquals(temp.getRoot(), dir);
                    else assertEquals(state.getParentFile(), dir);
                    assertEquals(phase >= 3, state.exists());
                });
        seed(cache, wire, "one");
        assertEquals(4, syncs.get());
    }

    @Test public void onlineClosuresCannotCaptureTheActivityOrPlayerStrongly() throws Exception {
        Class<?> bridge = Class.forName("com.gathin.tvhome.WallpaperPlayer$Online", false, getClass().getClassLoader());
        for (java.lang.reflect.Field field : bridge.getDeclaredFields()) {
            assertTrue(java.lang.reflect.Modifier.isStatic(field.getModifiers()));
        }
        int callbacks = 0;
        for (java.lang.reflect.Method method : bridge.getDeclaredMethods()) {
            if (!method.isSynthetic()) continue;
            callbacks++;
            assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
            for (Class<?> parameter : method.getParameterTypes()) assertNotEquals(WallpaperPlayer.class, parameter);
        }
        assertTrue(callbacks >= 2); // worker and main-thread deliveries each capture only a weak target
    }

    @Test public void imageRedirectFailuresRetainOldCache() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 1);
        OnlineWallpapers.Snapshot old = seed(cache, wire, "old");
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            wire.feed(feed("new"));
            wire.responses.add(response(status, 0, "", "", null));
            failed(cache, old, NOW + 12 * HOUR);
        }
    }

    @Test public void generatedFilenameCollisionNeverOverwritesAnExistingImage() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers.Snapshot old = seed(cache(wire, 1), wire, "old");
        File state = new File(temp.getRoot(), "online-wallpapers/state.json");
        byte[] before = Files.readAllBytes(state.toPath());
        byte[] original = Arrays.copyOf(png(), png().length + 1);
        original[original.length - 1] = 42;
        File[] collision = new File[1];
        OnlineWallpapers cache = create(temp.getRoot(), new OnlineWallpapers.Options(true, FEED, 12, 1),
                wire, file -> {
                    collision[0] = new File(file.getParentFile(), file.getName().replace(".tmp", ".jpg"));
                    Files.write(collision[0].toPath(), original);
                    return validImage(file);
                }, dir -> {});
        wire.feed(feed("new")); wire.image(png());
        assertThrows(IOException.class, () -> cache.refresh(old, NOW + 12 * HOUR));
        assertArrayEquals(before, Files.readAllBytes(state.toPath()));
        assertArrayEquals(original, Files.readAllBytes(collision[0].toPath()));
        assertTrue(validImage(old.entries.get(0).file));
    }

    @Test public void localRotationMoodsAndManualChoicesAreUnchanged() throws Exception {
        List<File> local = Arrays.asList(new File("morning.jpg"), new File("night.jpg"));
        assertEquals(local, WallpaperRotation.withOnline(local, Collections.emptyList()));
        assertEquals("night.jpg", WallpaperRotation.next(local, "morning.jpg").getName());
        Map<String, java.util.Set<String>> groups = Collections.singletonMap("night", Collections.singleton("night.jpg"));
        List<OnlineWallpapers.Entry> online = Collections.singletonList(
                new OnlineWallpapers.Entry("key", "", "Online", new File("image-123.jpg")));
        List<File> merged = WallpaperRotation.withOnline(local, online);
        assertEquals(Collections.singletonList(new File("night.jpg")), WallpaperMood.select(merged, groups, "night"));
        assertTrue(WallpaperRotation.chosen(local, null, "none").isEmpty());
        assertEquals(Collections.singletonList(local.get(0)), WallpaperRotation.chosen(local, null, "morning.jpg"));
        assertEquals(3, merged.size());
        assertEquals(2, local.size()); // merging never mutates the theme collection
    }

    private static class Connection extends HttpURLConnection {
        final Map<String, String> requestHeaders = new HashMap<>();
        boolean disconnected;
        int status = 304;
        Connection() throws Exception { super(new URL(FEED)); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public int getResponseCode() { return status; }
        @Override public void setRequestProperty(String name, String value) { requestHeaders.put(name, value); }
        @Override public String getHeaderField(String name) {
            return "ETag".equals(name) ? "\"new\"" : "Location".equals(name) ? "http://example.com/downgrade" : null;
        }
    }

    @Test public void transportRefusesToFollowRedirectsIncludingDowngrades() throws Exception {
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            Connection connection = new Connection();
            connection.status = status;
            try (OnlineWallpapers.Response response = OnlineWallpapers.request(connection, "", "")) {
                assertEquals(status, response.status);
                assertFalse(connection.getInstanceFollowRedirects());
                assertNull(response.body);
            }
            assertTrue(connection.disconnected);
        }
    }

    @Test public void standardHttpAdapterSetsTimeoutsValidatorsAndDoesNotFollowRedirects() throws Exception {
        Connection connection = new Connection();
        try (OnlineWallpapers.Response response = OnlineWallpapers.request(connection, "\"old\"", "date")) {
            assertEquals(304, response.status);
            assertEquals("\"new\"", connection.getHeaderField("ETag"));
            assertEquals(5000, connection.getConnectTimeout());
            assertEquals(8000, connection.getReadTimeout());
            assertFalse(connection.getInstanceFollowRedirects());
            assertFalse(connection.getUseCaches());
            assertEquals("\"old\"", connection.requestHeaders.get("If-None-Match"));
            assertEquals("date", connection.requestHeaders.get("If-Modified-Since"));
        }
        assertTrue(connection.disconnected);
        Connection unsafe = new Connection();
        try (OnlineWallpapers.Response ignored = OnlineWallpapers.request(unsafe, "a\r\nb", "x\ny")) {
            assertFalse(unsafe.requestHeaders.containsKey("If-None-Match"));
            assertFalse(unsafe.requestHeaders.containsKey("If-Modified-Since"));
        }
    }

    /** The X90K bug: first run, no cache, nothing on screen. The download must be shown at once, not never. */
    @Test public void firstRunWithEmptyCacheShowsTheDownloadImmediately() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers cache = cache(wire, 10);
        OnlineWallpapers.Snapshot cached = cache.load();
        assertTrue(cached.entries.isEmpty());
        // The cached (empty) snapshot arrives first, with nothing to show.
        assertTrue(WallpaperRotation.admitNow(true, "", cached.entries.size()));
        wire.feed(feed("a", "b"));
        wire.image(png());
        wire.image(png());
        OnlineWallpapers.Snapshot fresh = cache.refresh(cached, NOW);
        assertEquals(2, fresh.entries.size());
        assertTrue("a download with nothing displayed is admitted", WallpaperRotation.admitNow(false, "", 2));
        assertFalse("a download never swaps a picture already shown", WallpaperRotation.admitNow(false, "x.jpg", 2));
        assertFalse("an empty download is not admitted", WallpaperRotation.admitNow(false, "", 0));
        // And with wallpaper_rotation_minutes 0 the online collection still has a ticking clock.
        assertEquals(12 * HOUR, WallpaperRotation.interval(0, true, 12 * HOUR));
        assertEquals(0, WallpaperRotation.interval(0, false, 12 * HOUR));
        assertEquals(60 * 60_000L, WallpaperRotation.interval(60, true, 12 * HOUR));
        assertTrue(WallpaperRotation.due(0, NOW, WallpaperRotation.interval(0, true, 12 * HOUR)));
    }

    @Test public void refreshNowMakesAFreshCacheDue() throws Exception {
        Wire wire = new Wire();
        OnlineWallpapers.Snapshot fresh = seed(cache(wire, 10), wire, "a");
        OnlineWallpapers later = create(temp.getRoot(), new OnlineWallpapers.Options(true, FEED, 12, 10, NOW + 1),
                wire, OnlineWallpapersTest::validImage, dir -> {});
        assertFalse(cache(wire, 10).due(fresh, NOW + HOUR));
        assertTrue(later.due(fresh, NOW + HOUR));
    }

    @Test public void platformLogsFeedAndImageResults() throws Exception {
        Wire wire = new Wire();
        List<String> log = new ArrayList<>();
        OnlineWallpapers cache = new OnlineWallpapers(temp.getRoot(), new OnlineWallpapers.Options(true, FEED, 12, 10),
                new OnlineWallpapers.Platform() {
                    @Override public OnlineWallpapers.Response get(String url, String etag, String modified)
                            throws IOException { return wire.get(url, etag, modified); }
                    @Override public boolean valid(File file) throws IOException { return validImage(file); }
                    @Override public void sync(File directory) { }
                    @Override public void log(String message) { log.add(message); }
                });
        wire.feed(feed("a"));
        wire.image(png());
        cache.refresh(cache.load(), NOW);
        String all = String.join("\n", log);
        assertTrue(all, all.contains("feed HTTP 200"));
        assertTrue(all, all.contains("feed entries: 1"));
        assertTrue(all, all.contains("image ok"));
    }
}
