# Device profile: Sony XR-65X90K CN

This reference records the verified state used by the `sony-x90k-cn-maintenance` skill. Treat it as a device-specific profile, not a generic BRAVIA_VH21 recipe.

## Identity

- Retail model: Sony XR-65X90K (China market)
- Android model: `BRAVIA 4K VH21`
- Device: `BRAVIA_VH21`
- Product name: `BRAVIA_VH21_M_CN`
- Product codename observed before OTA: `Valhalla_CHD`
- Model year property: `22`
- Netflix model group: `SONYANDROIDTV2022M5`
- ADB security: `ro.adb.secure=1`

## Firmware history observed in this setup

Before OTA:

- Android 10 / API 29
- `BRAVIA_VH21_M_CN-user 10 QTG3.200305.006.S166 659381 release-keys`

After Sony system OTA:

- Android 12 / API 31
- `BRAVIA_VH21_M_CN-user 12 STT2.230505.001.S136 682241 release-keys`

Do not assume a future OTA preserves this package matrix. Re-probe after every firmware update.

## ABI / bitness

Post-OTA userspace remained 32-bit:

```text
ro.product.cpu.abi       = armeabi-v7a
ro.product.cpu.abilist   = armeabi-v7a,armeabi
ro.product.cpu.abilist32 = armeabi-v7a,armeabi
ro.product.cpu.abilist64 = <empty>
uname -m                 = armv8l
getconf LONG_BIT         = 32
```

Use `armeabi-v7a` or universal APKs. Do not assume an `arm64-v8a`-only APK will install.

## Launcher state before CleanYourTV handover

Observed HOME before replacement:

```text
com.oversea.aslauncher/.ui.main.MainActivity
```

Other launcher packages present include:

- `com.dangbei.TVHomeLauncher`
- `com.google.android.leanbacklauncher`

The safe launcher handover intentionally leaves all of them enabled on the first pass.

## Debloat state

After the three validated cleanup batches, `pm list packages -d` contained 39 packages. The exact matrix is in `package-matrix.md`.

`com.sony.dtv.b2b.hotelmode` is special: package state is disabled (`enabled=3`), but Sony still starts its persistent process after reboot. Verify the package state, not process absence.

## Recorded memory baseline after Batch 3

This snapshot is only a local before/after reference. It is not a general performance claim.

- `com.oversea.aslauncher`: about 116 MB PSS while it was HOME; about 236 MB RSS in the captured snapshot.
- `com.dangbei.TVHomeLauncher`: about 38 MB PSS while running as a foreground service in the same snapshot.
- `com.hpplay.happyplay.aw`: about 118 MB PSS in the same snapshot.
- `com.sony.dtv.b2b.hotelmode`: about 6.7 MB PSS despite the package being disabled, because it is persistent.

Repeat the same `dumpsys meminfo` collection under similar idle conditions after changing launchers before drawing conclusions.

## Validation features that must remain working

- HDMI inputs and external TV input routing
- BRAVIA Sync / CEC
- Audio output and volume hold/repeat
- Physical remote and Bluetooth pairing
- Input/source menu
- System Settings
- Standby/wake
- OTA update path
- DRM-related playback paths
