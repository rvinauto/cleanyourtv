# Package matrix: Sony XR-65X90K CN / Android 12 build 682241

This matrix records the package state verified on one XR-65X90K China-firmware unit. Do not apply it to another Sony model or firmware without a fresh audit.

## Pre-existing / already disabled before the three batches (7)

```text
com.sony.dtv.mysony
com.ktcp.tvvideo
com.sony.dtv.livingfit
com.sony.dangbeimarket
com.sony.dtv.b2b.vendorprotocol
com.sony.dtv.smarthelp
com.sony.dtv.b2b.softap
```

`com.ktcp.tvvideo` and `com.sony.dangbeimarket` are user-choice/content packages, not universal Sony bloat. Preserve the user's preference when reconstructing state on another unit.

## Batch 1: telemetry / remote support / demo (9)

```text
com.qterics.da.product
com.sony.dtv.da.service
com.sonyericsson.idd.agent
com.sony.dtv.sonybugreportsys
com.sony.dtv.usercontext
com.sony.dtv.demosupport
com.sony.dtv.demosystemsupport
com.sony.dtv.multiscreendemo
com.jd.smartservicetwo
```

## Batch 2: help / camera / sensing / kids / eco (10)

Apply the camera/sensing group only when the owner does not use BRAVIA CAM, gesture/video-chat, or distance-alert features.

```text
com.sony.dtv.customersupport
com.sony.dtv.helpguidecontents
com.sony.dtv.gestureapp
com.sony.dtv.sensingcamera.fwupdate
com.sony.dtv.sensingcamera.settings
com.sony.dtv.sensingfusionservice
com.sony.dtv.videochatrecord
com.sony.dtv.distancealert
com.sony.dtv.ecodashboard
com.sony.dtv.kidsprofileadapter
```

## Batch 3: B2B / hotel / commercial (13)

```text
com.sony.dtv.b2b.adminpassword
com.sony.dtv.b2b.deviceadminsettings
com.sony.dtv.b2b.frameworkextension
com.sony.dtv.b2b.hotelmode
com.sony.dtv.b2b.importexport
com.sony.dtv.b2b.ipcontrolsettings
com.sony.dtv.b2b.noderuntime.normal
com.sony.dtv.b2b.noderuntime.privilege
com.sony.dtv.b2b.noderuntimeextension.bpk
com.sony.dtv.b2b.noderuntimeextension.node_20_11_1
com.sony.dtv.b2b.prosettings
com.sony.dtv.b2b.rs232csupport
com.sony.dtv.b2b.webappruntimedrm
```

`com.sony.dtv.b2b.hotelmode` remains a persistent process after disable on this firmware. `pm list packages -d` / package enabled state is authoritative.

## Protected / never disable in this workflow

These are part of HDMI/input, CEC, DRM, OTA, remote, audio, platform, or Android core paths. This list is intentionally conservative.

```text
com.sony.dtv.tvx
com.sony.dtv.tvinput.provider
com.sony.dtv.tvinput.tuner
com.sony.dtv.tvinput.external
com.sony.dtv.tvinput.hardwaremanager
com.sony.dtv.tvapi
com.sony.dtv.sonysystemservice
com.sony.dtv.sonyvendorservice
com.sony.dtv.settingsprovider
com.sony.dtv.systemupdate
com.sony.dtv.update
com.sony.dtv.upgradesetup
com.sony.dtv.braviasyncmenu
com.sony.dtv.braviasyncservice
com.sony.dtv.braviasyncsetting
com.sony.dtv.partnerkeycn
com.sony.dtv.preinappfiltering
com.sony.dtv.audiooutput
com.sony.dtv.audiosysteminfoprovider
com.sony.dtv.remotecontrolsettings
com.google.android.tv.remote.service
com.android.bluetooth
com.android.systemui
com.android.settings
com.android.tv.settings
com.android.permissioncontroller
com.android.packageinstaller
android
```

Also protect all `com.mediatek.*`, `mtktvapi.agent`, Android module/networkstack packages, overlays/RROs, and providers unless a separate device-specific investigation proves otherwise.

## Optional-review packages: do not auto-disable

These may be removable for a specific household, but they provide real features and require a separate decision and regression test:

```text
com.hpplay.happyplay.aw
com.sonyericsson.dlna
com.sony.huey.dlna.renderersettings
com.sony.dtv.homenetwork
com.sony.dtv.HomeTheatreControl
com.sony.dtv.recapp
com.sony.dtv.notificationcenter
com.sony.dtv.searchapp
com.sony.dtv.voicesearch
com.sony.dtv.smartmediaapp
com.sony.dtv.browser.webappruntime
com.sony.dtv.browser.webappservice
com.sony.dtv.browser.webappplatform
com.sony.dtv.webapi.core
com.sony.dtv.iot
```

Do not turn this section into a fourth batch automatically.

## OTA note

A package named `screnmirroring.com` was present/disabled on the Android 10 installation and disappeared from the package list after the Android 12 OTA. This is an example of why package lists must be re-audited after firmware updates instead of blindly replaying an old list.
