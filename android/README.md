# LibrePods Hyper · Android 安装指南

[返回项目首页](../README.md) · [下载构建](https://github.com/Wtrwx/librepods-hyper/actions/workflows/ci-android.yml) · [反馈问题](https://github.com/Wtrwx/librepods-hyper/issues)

## 运行条件

- Android 13 或更高版本，支持经典蓝牙的 Android 手机，以及已配对的 AirPods。
- 基础控制通道是否需要 root 取决于具体蓝牙栈；本分支主要验证的是小米 HyperOS 的 root / Xposed 环境。
- 模拟 Apple 蓝牙身份、小米系统扩展和本分支测试的多设备组合需要启用相应 Hook。
- 辅助听力、手势和自适应模式等功能取决于耳机型号与固件。

当前主要测试设备为小米 17（Android 16）、iPhone XR（iOS 16.0）与 AirPods Pro 3。

## 安装 APK 与启用模块

1. 从本仓库 [Releases](https://github.com/Wtrwx/librepods-hyper/releases) 或 [Android CI](https://github.com/Wtrwx/librepods-hyper/actions/workflows/ci-android.yml) 下载 FOSS APK。
2. 安装并打开 **LibrePods Hyper**，完成蓝牙、通知等权限设置。悬浮窗和通话权限按需要授予。
3. 在 LSPosed / Vector 中启用 LibrePods Hyper 模块。框架须支持工程使用的 libxposed API 101。
4. 选择手机上实际存在的对应作用域：

| 作用域 | 包名 | 用途 |
| --- | --- | --- |
| 系统蓝牙 | `com.android.bluetooth` 或 `com.google.android.bluetooth` | AirPods 控制通道与 Device ID Hook |
| 小米蓝牙扩展 | `com.xiaomi.bluetooth` | 小米连接兼容与系统电量集成 |
| 系统设置 | `com.android.settings` 或 `com.google.android.settings` | 系统蓝牙设备信息集成 |

5. 按 App 提示完成兼容性设置并重新加载蓝牙；系统要求时重启手机。重新连接 AirPods 后确认 App 可以读取耳机状态。

本项目保留原应用 ID `me.kavishdevar.librepods` 与模块 ID，方便现有安装迁移。同一签名可覆盖更新；签名不同的上游或第三方 APK 无法直接覆盖，安装前先处理已有应用的数据备份。

## 多设备与自动切换

在 App 设置中：

1. 开启「模拟 Apple 设备」，按提示重新加载蓝牙。
2. 开启「允许 Smart Routing 自动切换」。默认关闭，需要用户主动开启。
3. 开启耳机的「自动连接」。
4. 在 Android 和另一台设备上分别完成 AirPods 配对，然后测试双向播放与连接顺序。

「辅助听力控制通道」独立控制 ATT 相关功能，默认关闭。只有需要对应的听力 / 通透自定义设置时再开启，并在修改后重新连接耳机。

最多支持两台主机。自动切换通过耳机的蓝牙控制通道协调，无需两台手机之间有网络连接。

## MIUIx 外观

在「App 设置 → 外观」中选择 **MIUIx**。也可以切换到 Apple 或 Material 3，外观选择会保存并立即应用。MIUIx 随系统浅色 / 深色模式调整。

## 可选：作为系统应用安装

CI 同时提供 root 模块 ZIP。通过兼容的 Magisk / KernelSU 模块管理器安装并重启，可为应用提供系统蓝牙相关权限；这是 APK 之外的可选系统集成方式。

模块不替代 Xposed 作用域配置，也不会自动开启模拟 Apple 身份。升级时让 App 与系统模块使用同一签名与对应版本。当前 root 模块采用手动更新，避免继承上游版本的自动更新源。

## 常见问题

| 现象 | 检查项 |
| --- | --- |
| 蓝牙已连接，App 无法读取状态 | 确认 Bluetooth 作用域实际加载模块、兼容性设置完成，重新连接后查看 L2CAP 错误 |
| 看不到模拟 Apple 身份选项 | 确认 Xposed 可用，且系统蓝牙作用域已启用 |
| 小米系统电量异常 | 确认 `com.xiaomi.bluetooth` 作用域，核对模块版本及系统扩展是否加载 |
| 切换到 iPhone 后 Android 无法接回 | 确认自动接管与自动连接已开启，再暂停 / 播放触发新的播放意图；记录两端的连接顺序与播放器 |
| 找不到 GitHub 下载产物 | 打开成功的 Android CI 运行记录，在 Artifacts 中下载 APK 或 root 模块压缩包 |

提交问题时附上耳机固件、Android / HyperOS 版本、Xposed 框架、具体播放器及完整复现步骤。导出日志前检查并移除密钥和个人信息。

## 本地开发

```sh
cd android
./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug
./gradlew :app:zipDebugModule
```

使用 JDK 21、Android SDK 37、NDK 30.0.14904198、CMake 3.22.1。工程包含 FOSS 和 Play flavor，本分支的日常构建使用 FOSS。原生 Hook 的独立回归工具说明见 [src/test/cpp/README.md](./app/src/test/cpp/README.md)。
