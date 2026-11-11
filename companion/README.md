# CleanYourTV companion

An optional Android phone app that manages a TV over wireless ADB on the same LAN: list and disable
apps, apply a debloat list, install an APK. It never claims `HOME`, so the phone keeps its own launcher.

Chinese guide, build steps and limitations: [README.zh-CN.md](README.zh-CN.md).

```bash
cd companion
printf 'sdk.dir=/path/to/Android/Sdk\n' > local.properties
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

`minSdk` 26, plain Java, no AndroidX, no telemetry. Port 5555 is plaintext ADB: keep it on your own
network and turn Network debugging off when you are done. See [docs/REMOTE.md](../docs/REMOTE.md)
for reaching a TV from outside the LAN.
