# Online Wallpapers handoff — 2026-10-23

This is a source/build handoff for the `rvinauto/cleanyourtv` fork, on
`feature/online-wallpapers`, based on local baseline
`c5d75e8e8071d6545e78ccbb6b4a303774f2e048`. Online Wallpapers is **not tested on the TV**.
Git backup is authorized; TV connection, installation, deployment, merging main and creating a
release are outside this handoff. No TV operations were performed for this feature.

## Implementation

- [`OnlineWallpapers.java`](../launcher/app/src/main/java/com/gathin/tvhome/OnlineWallpapers.java)
  implements the private transactional cache, Feed parsing and conditional HTTP transport.
- Feed and image URLs must use HTTPS. Credentials, fragments and unsafe paths are rejected.
  HTTP redirect following is disabled for both Feed and images: redirects are rejected, including
  HTTPS-to-HTTP downgrade. Remote IDs/URLs cannot choose local paths or modify configuration.
  Cache filenames are generated locally; persisted identities are hashed.
- ETag / Last-Modified produce If-None-Match / If-Modified-Since requests. HTTP 304 is a successful
  check that atomically updates the refresh timestamp, preventing repeated requests on every Home
  creation during the refresh interval.
- Disabled means no online work. Fresh cache is read without networking. Only expired cache triggers
  background refresh. There is no polling service or new runtime dependency.
- A lazy process-wide single-flight queue has at most one worker; Activity recreation cannot cause
  concurrent downloads. The idle thread retires. Background tasks use application context and a
  weak reference for UI delivery; closing the player cancels its future.
- Connect/read timeouts are 5/8 seconds, with a 30-second copy deadline. Feed size is limited to
  256 KiB / 200 entries. Each image has a hard **10 MiB** limit on both advertised and received bytes.
- Shared image validation checks bounds before pixel allocation, using long arithmetic and an
  **8,400,000-pixel** limit for online originals. 1920×1080 and UHD 3840×2160 are allowed. A sampled
  decode must also succeed before accepting the file.
- Downloads use `.tmp` files, file fsync, decode validation and atomic rename. Generated filename
  collisions fail rather than overwrite a valid cache image. Failure removes uncommitted temporary
  files without damaging existing cache.
- `state.json` records the last successful refresh, ETag, Last-Modified and current cached entries
  (generated filenames, hashed identities and captions). It contains no credential values or URLs.
  State is written to `state.tmp`, fsynced and atomically replaced. Directory fsync makes image names
  durable before publishing state; state directory/parent durability is checked before pruning.
- Cache pruning occurs only after a valid new state is committed and metadata sync succeeds. An
  exception after state rename keeps both generations instead of deleting newly referenced files.
  Feed/HTTP/DNS/TLS/JSON/disk failures, empty Feed, corrupt images and partial-download failures retain
  the previous valid state/cache. Simulated interrupted commits are covered by JVM tests.
- [`WallpaperPlayer.java`](../launcher/app/src/main/java/com/gathin/tvhome/WallpaperPlayer.java)
  integrates pending cache snapshots into the next normal rotation point, without interrupting the
  current wallpaper. With rotation disabled, the new collection becomes eligible on Home recreation.
- With no online cache, the existing `wallpaper.jpg`, `wallpapers/`, theme wallpaper and background
  remain the fallback. Shuffle/sequential order, captions, moods and local selections are retained.
  The existing behavior remains when the online option is off.

## Configuration

Resource defaults in `launcher/app/src/main/res/values/config.xml` and equivalent theme JSON:

```json
{
  "online_wallpaper": false,
  "online_wallpaper_feed": "",
  "online_wallpaper_refresh_hours": 12,
  "online_wallpaper_cache_count": 10
}
```

- Refresh range: **1–168 hours**. Cache range: **1–30 images**.
- Private cache: **`filesDir/online-wallpapers/`**, separate from theme.ps1-managed wallpapers.
- Wallpaper disabled, an explicit local selection, or None suppresses online work.
- The optional Feed format and lifecycle are documented in
  [`themes/README.md`](../themes/README.md#online-wallpapers-optional).
- The only new Android permission is INTERNET. No runtime dependency was added; `org.json` is a
  JVM test-only dependency. Existing runtime RecyclerView/DynamicAnimation dependencies are unchanged.

## Files included in this feature

- `OnlineWallpapers.java` and `OnlineWallpapersTest.java` — implementation and cache/transport tests.
- `Config.java`, `WallpaperPlayer.java`, `WallpaperRotation.java`, `config.xml` — optional settings,
  worker/lifecycle integration and local/online rotation. Preserve the confirmed `tile_menu=true`
  and `reorder_button=false` settings.
- `AndroidManifest.xml` — INTERNET permission.
- `WallpaperSizeTest.java` — bounds and arithmetic regression coverage.
- `Cards.java`, `app/build.gradle` — compile out debug-only self-checks with BuildConfig.DEBUG,
  retaining production validators and reducing release size; add test-only JSON support.
- `README.md`, `themes/README.md`, this document and
  [`build-launcher-apk.yml`](../.github/workflows/build-launcher-apk.yml) — documentation/build handoff.

## Verified local results

These are local release-closeout results, not a GitHub-hosted build or device test:

| Check | Result |
|---|---|
| `testReleaseUnitTest` | **226 Android JVM tests passed**, 0 failures/errors |
| Online wallpaper tests (included above) | **35 passed** |
| `lintRelease` | **0 errors**, **75 pre-existing warnings** |
| `assembleRelease` | Pass; APK **490591 bytes** |
| Strict APK gate | **< 491520 bytes**; **929-byte margin** |
| Release size reduction from 492203 bytes | **1612 bytes** |
| Configuration/table/theme checks | **28 passed** |
| Screenshot-only checks | **4 missing-key subtest failures remain** |
| Runtime dependencies added | **None** |

The four screenshot omissions are `online_wallpaper`, `online_wallpaper_feed`,
`online_wallpaper_refresh_hours` and `online_wallpaper_cache_count` in `docs/switches.md`.
No screenshot requirement or test was weakened. The screenshot-inclusive targeted run had 31 tests
and these four subtest failures. Do not describe the full repository suite as passing.

Local builds used the installed Android Studio JBR (JDK 21), Java 17 source/target, SDK 34 and
build-tools 34.0.0. The workflow explicitly uses JDK 17; its APK size must pass independently.
Debug compilation and bytecode inspection confirmed the self-check remains in debug and is absent
from release. DEX inspection found no test-library or test-class definitions in the release APK.
Private logs under ignored `work/online-wallpapers/` are not part of the Git handoff.
Handoff checks also passed: `git diff --check`, actionlint 1.7.12, Bash syntax, missing-signing-secret
failure, and strict size-gate boundary simulations at 490591, 491519, 491520 and 491521 bytes.
The 28 configuration/table/theme checks were rerun successfully during handoff. The existing
Android XML reports and exact local APK size were rechecked without rebuilding unchanged sources.

## Not yet verified

- **Not deployed to Sony XR-65X90K**; actual Online Wallpapers device status: **Not tested**.
- No real HTTPS Feed integration test.
- No actual network-disconnect/recovery test.
- No real power-cut test of state/cache atomicity or filesystem durability.
- No long-duration wallpaper rotation test.
- The four screenshot-only checks above remain unsatisfied.
- The new GitHub Actions job has not run; signing-secret presence has not been verified.

Unit tests simulate failures and commit stages; they do not establish physical power-loss behavior.

## Recorded Sony stable baseline — preserve, do not modify

This is the previously user-validated baseline, not a fresh device inspection:

- Sony XR-65X90K China firmware, Android 12 / API 31, build 682241.
- HOME: `com.gathin.tvhome/.MainActivity`.
- `tile_menu=true`; `reorder_button=false`.
- **44 disabled packages**; Dangbei `com.dangbei.TVHomeLauncher` remains **disabled**.
- InputKeyService, HomeKeyService and RestoreKeyServices accessibility helpers are **not enabled**.
- HDMI / CEC / remote / standby-wake were normal at that baseline, as were playback apps.
- Do not alter HOME, disabled packages, helper state or TV configuration for this source handoff.

Existing staged Sony maintenance skill files and local batch lists are unrelated changes. Preserve
them locally and exclude them from this feature commit. Do not include `work/`, APKs, `.gradle/`,
`build/`, signing keys or secret values in Git.

## Manual GitHub APK workflow and signing

Workflow: [Build X90K Launcher APK](../.github/workflows/build-launcher-apk.yml).
It has only `workflow_dispatch`, read-only repository permissions, Java 17, SDK 34 / build-tools
34.0.0, and runs `testReleaseUnitTest`, `lintRelease`, `assembleRelease`. It prints exact APK bytes,
SHA256 and full commit SHA, then enforces **strictly less than 491520 bytes** before uploading only
`launcher/app/build/outputs/apk/release/app-release.apk` with `actions/upload-artifact@v4`.
Artifact: `CleanYourTV-X90K-<short-sha>`, retained for 14 days. No release or deployment is created.

Required repository Actions secret:

| Secret | Purpose |
|---|---|
| `ANDROID_DEBUG_KEYSTORE_BASE64` | Base64 of the user's **existing** debug.keystore used for the currently installed Launcher; never a newly generated key |

Configure it privately in the fork's **Settings → Secrets and variables → Actions**. Never paste
its contents into source, an issue, logs or this document. This handoff does not upload the key or
configure the secret. Base64 is an encoding, not encryption.

Release currently uses `signingConfigs.debug`, with the standard debug alias and default signing
parameters supplied by the Android Gradle plugin. **No additional store/key-password secrets are
required for the existing standard debug keystore.** No password values are recorded here. A
nonstandard or incorrect keystore fails signing and must not trigger generation of another key.
Missing/empty/invalid Base64 fails before Gradle runs; the restored file is checked again before
the build. The workflow never generates a key and removes the restored file in its final cleanup.
The artifact upload path cannot include the keystore. Using a different key prevents updating the
already-installed app; retain the same signing identity for any later authorized installation.

GitHub requires a workflow with `workflow_dispatch` to exist on the repository's default branch
before manual dispatch is available; see [GitHub's manual-run documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).
At handoff, the fork's default branch is `main` and it does not contain this new workflow. Pushing
only `feature/online-wallpapers` therefore does **not** immediately provide a working Run workflow
button. No main merge or default-branch change is authorized or performed here.

After the owner separately reviews/approves adding the workflow entry to the default branch and
configures the signing secret: **Actions → Build X90K Launcher APK → Run workflow → choose
`feature/online-wallpapers` → Run workflow**. Check the commit SHA, size and SHA256 in the summary,
then download `CleanYourTV-X90K-<short-sha>` from that run's Artifacts. A hosted build is still not
device acceptance, and a passing build does not resolve the four outstanding screenshot checks.

## Resume and rollback

1. Review this feature branch and the unresolved checks before authorizing any TV access.
2. Set up the signing secret and the default-branch workflow entry separately if a hosted build is
   wanted. Do not merge the untested feature into main just to expose the button.
3. Only after explicit device-test authorization, verify a real HTTPS Feed, offline fallback,
   recovery, prolonged rotation and physical power-loss behavior; capture real screenshots.
4. Preserve the recorded Sony baseline throughout later testing. Do not run another debloat batch.

To leave online work off, keep `online_wallpaper=false` (the default) and the existing local theme.
No cache migration is required for existing installations. No TV rollback is needed for this
source-only handoff because no installation or device change was made.

## Update 2026-10-24 (Asia/Shanghai) — first-run fix, built-in Bing default, settings UI

**Root cause found on the X90K (config was loaded correctly):** on a first run with an empty cache the
downloaded snapshot arrived with `initial=false` and was only admitted when the rotation clock was due;
with `wallpaper_rotation_minutes` 0 the interval was 0 and `WallpaperRotation.due()` never returned true,
so a download was never shown in that Home instance. OnlineWallpapers also logged nothing.

Changes (commit `efc6882` on `feature/online-wallpapers`):
- `WallpaperPlayer.receiveOnline`: a download is shown at once when nothing is displayed
  (`WallpaperRotation.admitNow`). Rotation interval falls back to the refresh interval when online and
  rotation is 0 (`WallpaperRotation.interval`). A failed refresh retries once when the default network
  becomes available. Logcat tag **`TvHome.Online`** (feed HTTP status, entries, image ok/fail, exception
  class). Status (last refresh, count, last error) in shared prefs `online_wallpaper_status`.
- Defaults in `res/values/config.xml`: `online_wallpaper=true`, feed
  `https://cdn.jsdelivr.net/gh/rvinauto/cleanyourtv@wallpaper-feed/feed.json`, `wallpaper_rotation_minutes=60`.
  Precedence: Launcher settings choice > theme `config.json` > resources. A theme push/reset clears the
  on-TV online choices like the other choices.
- Launcher settings: **Online wallpaper** row (en / zh-CN / zh-TW; other locales fall back to English):
  on/off, source (Bing jsDelivr / Bing GitHub raw / custom https URL via TV keyboard), refresh 1–168 h,
  cache 3–30, rotation interval, Refresh now, Clear downloaded pictures; status line under the row.
- APK size gate raised to **524288 bytes** (ci.yml, build-launcher-apk.yml, AGENTS.md). versionCode 8.
- `main`: `0a9e143` Bing feed workflow prefers cn.bing.com (only if 200 without redirect) and purges
  jsDelivr after publishing; `679d5d2` same size gate in build-launcher-apk.yml.

Verified (box + GitHub Actions):
| Item | Result |
|---|---|
| Android JVM tests (local) | **229 passed** (OnlineWallpapersTest 38, incl. first-run empty-cache test) |
| lint (local) | **0 errors**, 74 warnings |
| Python suite | 663 run, **4 failures = the known screenshot-only `online_wallpaper*` keys**, 3 skipped |
| GitHub run | [40049414767](https://github.com/rvinauto/cleanyourtv/actions/runs/40049414767) success, commit `efc68821f791280fa462e8a9f20a90eea07160e5` |
| APK size | **499691 bytes** (< 524288; margin 24597) |
| APK SHA256 | `d7b794d3dbc59cd9a4df40396b1d5f2b8bd745c5db624713a6434a8321d16430` |
| Signing cert SHA-256 | `012B76CC…39DEF24F` — matches the TV's key |
| cn.bing.com `_UHD.jpg` | 200, image/jpeg, no redirect (checked from a non-China host only) |
| jsDelivr feed URL | 200 application/json (non-China host) |

**NOT TESTED:** v8 not installed on the Sony X90K; online wallpaper display on the TV; jsDelivr / cn.bing.com
reachability from the TV's network in mainland China; Settings UI on the TV (D-pad, keyboard input);
network-retry path on a real network; offline → recovery; power loss during cache write; long rotation;
the 4 screenshot-only requirements; the jsDelivr purge step (runs on the next scheduled feed job).
