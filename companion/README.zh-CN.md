# CleanYourTV 手机版（companion）

一个可选的 Android 手机应用：手机和电视连同一个局域网，通过无线 ADB 管理电视上的应用、精简系统、把 APK 装到电视。它替代不了电脑上的 `tools/`，只是把最常用的几步搬到手机上。

- 包名 `com.gathin.tvhome.companion`，`minSdk` 26（Android 8.0），纯 Java，无 AndroidX。
- 只声明 `LAUNCHER`，**从不声明 `HOME`**：手机桌面不会被它接管。
- 只连用户手动填写的地址，不扫描网段，不采集数据，没有统计和联网服务。
- 只停用，不卸载，不用 root。

## 编译

需要 JDK 17 和 Android SDK platform 34、build-tools 34.0.0。仓库里没有 `local.properties`，自己建一个：

```bash
cd companion
printf 'sdk.dir=/path/to/Android/Sdk\n' > local.properties
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

APK 在 `companion/app/build/outputs/apk/debug/app-debug.apk`。`*.apk` 已被根目录 `.gitignore` 忽略，不要提交。

## 在电视上准备

1. 电视和手机连同一个 Wi-Fi。
2. 电视：设置 → 设备偏好设置 → 关于 → 连点「版本号」7 次，返回开发者选项。
3. 打开 **USB 调试**；有 **网络调试 / ADB 调试** 开关的也打开。
4. 记下电视的局域网地址（设置 → 网络，或路由器列表）。端口默认 `5555`。

第一次连接时电视会弹出「允许 USB 调试吗？」，用遥控器选允许。手机版在应用私有目录生成一对 RSA 密钥，之后复用。

## 功能

| 页面 | 做什么 |
|---|---|
| 连接 | 填 `IP:端口`，保存最近 6 台电视，点一下重连。 |
| 应用管理 | `pm list packages -3` 与 `-s` 合并列表；打开、停用（`pm disable-user --user 0`）、启用（`pm enable`）、强行停止。Android 核心包显示为「受保护」，按钮不可用。 |
| 精简优化 | 粘贴精简清单（每行一个包名，`#` 注释）。执行前把电视当前已停用列表备份到手机私有目录；「恢复上次备份」逐个 `pm enable`。 |
| 安装 APK | 通过系统文件选择器选 APK，推到 `/data/local/tmp/cleanyourtv-companion.apk`，再 `pm install -r`。另有「把 CleanYourTV 桌面装到电视」入口，装完提示怎么设为默认 Home。 |
| 截图 | `screencap -p` 拉回手机显示。直播电视和部分流媒体应用会返回黑屏，这是电视的限制。 |

核心包黑名单与 `tools/disable.sh` 的 `CORE_RE`、`tools/recovery.py` 一致，并在界面和 `AdbSession` 执行层各拦一次。改动其中一处时，三处要一起改。

安装桌面后，默认 Home 仍需在电视上设置：设置 → 应用 → 默认应用 → 主屏幕，或按根目录 README 运行 `cmd package set-home-activity`。手机版不会替你切换 Home，也不会停用原桌面。

## 安全

- 5555 端口是明文、无认证的 ADB。只在可信局域网使用，不要把端口映射到公网。远程访问请按 [`docs/REMOTE.md`](../docs/REMOTE.md) 走 Tailscale 之类的私有网络。
- 用完在电视上关掉网络调试，并撤销调试授权。
- 手机版不保存电视内容，只保存：最近连接的 `IP:端口`、应用私有目录里的 ADB 密钥、一份「上次停用列表」备份。
- 卸载手机版会一起删掉密钥和备份；需要恢复时先在电视上手动 `pm enable`，或重装后重新连接。

## 已知限制

- 只支持传统 TCP ADB（5555）。Android 11+ 的「无线调试」配对流程（随机端口 + 配对码）尚未实现；需要先在电视上打开 5555，参见 [`PROMPT.md`](../PROMPT.md)。
- 精简清单需要自己粘贴，手机版不内置按机型区分的列表；列表在 [`examples/`](../examples/)。
- 没有在真机上验证过，只在 JVM 单元测试和编译层面验证。
