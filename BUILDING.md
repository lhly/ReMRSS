# 从源码构建 ReMRSS

需要调试或自行签名时，使用与目标版本一致的 tag 源码。直接安装软件请前往 [正式版下载](https://github.com/lhly/ReMRSS/releases/latest)。

宿主 APK 的最低系统版本为 Android 16（API 36）；独立 Android SDK AAR 仍以 API 24 为最低版本，集成方式见 [SDK 指南](sdk/android/README.md)。

## 准备环境

- JDK 21。
- Android SDK Platform 37.0、Build Tools 37.0.0、NDK 27.0.12077973。
- Gradle、Android Gradle Plugin 和 Kotlin 使用仓库内的 Wrapper 与版本配置，不需要全局安装 Gradle。

在 Android Studio 打开 `android/`，将 Gradle JDK 设为 JDK 21，并通过 SDK Manager 安装以上组件。SDK 路径写入本机 `android/local.properties` 的 `sdk.dir`，或使用 `ANDROID_HOME`；不提交本机路径配置。

## 构建调试版

在项目根目录运行：

```sh
./android/gradlew -p android :app:assembleDebug
```

APK 位于 `build/app/outputs/apk/debug/app-debug.apk`。调试版包名为 `top.zainzh.rearscreen.debug`，协议为 `mrss-debug://`，与正式版的配置和权限分开。

## 使用自己的密钥构建正式版

1. 准备自己的签名密钥。
2. 将 `android/key.properties.example` 复制为 `android/key.properties`，填写自己的密钥路径、类型、别名和密码。`storeFile` 的相对路径以 `android/app/` 为基准。
3. 在项目根目录执行：

```sh
./tools/build_release.sh
```

APK 位于 `build/app/outputs/apk/release/app-release.apk`。正式版启用 R8 和资源压缩；版本读取根目录 `version.properties`。签名密钥及含密码的配置仅保存在本机，不得提交或放入发行附件。

自行签名的 APK 不能覆盖不同签名的官方安装包。若需要保留现有数据，请继续使用相同包名、相同签名的更新；不要为安装自签名包直接卸载正在使用的版本。

## 对应源码与许可证

已发布版本的源码和构建说明保留在对应 tag 中。根目录 [LICENSE](LICENSE) 和 [NOTICE](NOTICE) 是 APK 内离线许可与归属信息的来源；修改或再分发时保留相应内容。

[用户指南](docs/README.md) · [项目首页](README.md)

## 隔离设备测试

直接 Gradle 安装、卸载及连接设备测试任务会在设备动作前失败。先构建 app 与 instrumentation APK：

```sh
./android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest
```

再由设备操作负责人提供**本地私有 session JSON**（不提交、不转发），使用以下命令在 root 专用模拟器运行单个 instrumentation 测试（类或单个 `#method`）：

```sh
python3 tools/guarded_device.py instrument \
  --session /path/to/private-session.json \
  --app-apk build/app/outputs/apk/debug/app-debug.apk \
  --test-apk build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --runner androidx.test.runner.AndroidJUnitRunner \
  --class top.zainzh.rearscreen.DeviceIsolationSmokeTest
```

session 必须明确给出 `schema_version: 1`、`server: {host, port}`、`serial` 和 `target: {kind, expected_device, expected_model}`。模拟器再提供 `avd_name`；真机的 `expected_serial` 由设备操作负责人在私有 session 中填写，且必须匹配工具允许的固定真机。只接受 loopback 私有端口（不是 5037/15001）及唯一 `device` 状态目标；模拟器需为名以 `remrss-root-` 开头的 root 专用 AVD，且身份、QEMU 标记和 AVD 名均匹配。

`instrument --class` 必须显式指定一个 `top.zainzh.rearscreen` 下的测试类，可追加单个 `#method`；拒绝多类、其他 namespace 与 shell 元字符。APK manifest 中的 instrumentation runner 及 targetPackage 必须匹配同一组 app/test APK。应用包可为 `.debug` 或 `.rootdebug`，不强制 debuggable；示例中的 `DeviceIsolationSmokeTest` 断言 `.debug` namespace，使用 `:app:assembleDebug` 构建。入口拒绝覆盖预先存在的包；测试退出后只清理本轮新装的调试与测试 APK，不回滚其他设备状态，也不限制 instrumentation 本身执行的操作。

模拟器默认无需额外授权。真机安装/运行入口默认关闭；只有 root 已取得本次设备任务的明确用户授权，核对本轮成对构建的 app/test APK 与具体测试方法，并评估其副作用及恢复步骤后，才可针对固定 phone session 显式追加 `--allow-phone-debug-tests`。APK 包名和 manifest 匹配仅用于检查配对关系，不构成对构建来源的信任。不要运行会改动字体、主题、全局动画等系统设置的测试，除非任务负责人已安排相应恢复。

`verify` 可用 `python3 tools/guarded_device.py verify --session /path/to/private-session.json` 做只读身份核验，供 root 在真机UI或Release更新前复用；它不安装、运行或清理包。ADB 路径可用 `--adb` 或 `ANDROID_HOME` 提供，aapt 路径可用 `--aapt` 或 SDK 提供。
