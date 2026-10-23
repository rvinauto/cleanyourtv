---
name: sony-x90k-cn-maintenance
description: Safely maintain, debloat, validate, benchmark, switch, and restore the launcher on a Sony XR-65X90K China-firmware BRAVIA_VH21 using this CleanYourTV repo. Use after OTA upgrades, when reapplying the known cleanup, when replacing Home, or when checking regressions on this exact device family.
---

# Sony XR-65X90K CN maintenance

Use this skill only for the Sony XR-65X90K China firmware family described in `references/device-profile.md`. Read that file and `references/package-matrix.md` before changing the TV.

## Non-negotiable safety rules

- Probe the real TV first. If the device/platform/region does not match `BRAVIA_VH21` + `M_CN`, audit only and stop before package changes.
- Never root, flash, factory-reset, or use `pm uninstall` on system packages.
- For debloat, use only `pm disable-user --user 0` through this repo's `tools\disable.ps1`.
- Never set `FORCE_HOME`, `TVINPUT_ALLOW`, `FORCE_SETUPWRAITH`, or similar bypass variables to defeat a guard. A guard warning means stop and report.
- Never disable packages listed under **Protected / never disable** in `references/package-matrix.md`.
- Do not disable the current Home launcher during a launcher handover. Keep `com.oversea.aslauncher` and `com.dangbei.TVHomeLauncher` enabled as fallbacks unless the user explicitly asks later and a separate recovery plan has been verified.
- Do not enable the launcher's optional Input/Home accessibility helpers on the first pass. Only add one after a real remote button is proven not to work without it.
- Do not treat the persistent `com.sony.dtv.b2b.hotelmode` process as a failed disable; verify package enabled state instead.

## Connect and snapshot

From the repository root on Windows PowerShell:

```powershell
if (-not $env:TV_HOST) { throw 'Set TV_HOST to the TV IP first.' }
tools\venv\Scripts\python.exe -I tools\tv.py --probe
tools\venv\Scripts\python.exe -I tools\tv.py "pm list packages"
tools\venv\Scripts\python.exe -I tools\tv.py "pm list packages -d"
tools\venv\Scripts\python.exe -I tools\tv.py "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
tools\venv\Scripts\python.exe -I tools\tv.py "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
```

Record the exact pre-change HOME component. Do not guess it later.

Also collect a baseline before a launcher change:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py "dumpsys meminfo | head -80"
tools\venv\Scripts\python.exe -I tools\tv.py "dumpsys activity processes"
```

## Firmware and ABI gate

The known post-OTA target is Android 12 / API 31, build ending `682241`. The Android userspace is 32-bit (`armeabi-v7a,armeabi`; `abilist64` empty; `LONG_BIT=32`).

If the build differs, do not blindly reapply the package matrix. Re-enumerate packages and compare first. OTA updates can add, remove, or re-enable packages.

## Reapply the known cleanup

The three validated lists are in `assets/`:

- `batch1-telemetry-demo.txt`
- `batch2-help-camera.txt`
- `batch3-b2b-hotel.txt`

Before each batch:

1. Compare the target list with `pm list packages` and `pm list packages -d`.
2. Leave already-disabled packages alone unless restoring a known state after reset/update.
3. Run only one batch at a time through `tools\disable.ps1`.
4. Verify `pm list packages -d` after the batch.
5. Test Home, Settings, Inputs, HDMI, CEC, volume hold/repeat, mute, Bluetooth remote, standby/wake, and normal playback apps.
6. Reboot once and verify the disabled state survived.

Example:

```powershell
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 `
  -TvHost $env:TV_HOST `
  .agents\skills\sony-x90k-cn-maintenance\assets\batch3-b2b-hotel.txt
```

A fully applied known state contains 39 disabled packages: 7 pre-existing/user-disabled packages plus 9 + 10 + 13 from the three validated batches. See `references/package-matrix.md` for the exact matrix.

## Safely replace Home with CleanYourTV

The known pre-handover Home in this setup is `com.oversea.aslauncher`, but always query the real TV again and save the exact component before changing anything.

First install and hand over Home while deliberately leaving every existing launcher enabled:

```powershell
powershell -ExecutionPolicy Bypass -File launcher\build-and-install.ps1 `
  -TvHost $env:TV_HOST `
  -StockLauncher none
```

Do not pass `-InputKeyService`, `-HomeKeyService`, or `-RestoreKeyServices` on the first install.

The install is successful only if all of the following are true:

- APK installation succeeds.
- `com.gathin.tvhome/.MainActivity` starts with `Status: ok`.
- HOME resolves to `com.gathin.tvhome/...` after the handover.
- Pressing the physical Home button returns to the new launcher.
- The physical Input/Settings/app keys still work as expected.
- HDMI inputs, CEC, audio, volume hold/repeat, Bluetooth remote, standby/wake, and playback remain normal.

Do not disable `com.oversea.aslauncher` or `com.dangbei.TVHomeLauncher` in the same session. Keep them as fallback until the new launcher has survived at least one reboot and real remote testing.

## Reboot validation

After a successful change:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py "svc power reboot"
```

An old ADB connection may time out while the TV is rebooting. That is not itself a failure. After the TV returns, re-run `--probe`, HOME resolution, and `pm list packages -d`.

## Launcher benchmark

Once the new launcher is stable, collect the same memory commands as the baseline. Compare PSS/RSS under similar idle conditions; do not claim a memory win from different uptime/workload snapshots.

The recorded baseline after Batch 3 had `com.oversea.aslauncher` as Home with roughly 116 MB PSS. Treat this only as this device's baseline, not as a general benchmark.

## Restore Home

Use the exact pre-change HOME component saved during the snapshot step:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py "cmd package set-home-activity --user 0 <saved-package>/<saved-activity>"
tools\venv\Scripts\python.exe -I tools\tv.py "input keyevent 3"
```

Verify that HOME now resolves to the saved launcher and that the physical Home key works. Do not uninstall `com.gathin.tvhome` unless the user explicitly asks after restoration is proven.

For package restoration, use the recovery record created by `tools\disable.ps1` / `tools\restore.ps1`; do not invent enabled states.

## Stop conditions

Stop immediately and report instead of improvising if any of these occurs:

- Device/build/region mismatch.
- A target package is missing or has materially different responsibilities.
- `disable.ps1` emits a HOME, core-package, TV-input, or network/setup safety warning.
- APK install or explicit launcher start fails.
- HOME resolves to setup wizard/fallback/unknown after handover.
- HDMI/CEC/audio/remote/input/settings behavior regresses.
- ADB cannot reconnect after reboot.

## Final report

Report only the actions actually performed, package-state deltas, HOME before/after, reboot verification, any warnings, and before/after memory measurements. Do not continue into optional package removal without a new explicit request.
