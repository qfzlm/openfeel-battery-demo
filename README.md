# 弱水时砂 OpenFeel 电量（Android）

[![Android CI](https://img.shields.io/badge/Android%20CI-enabled-2ea44f)](https://github.com/qfzlm/openfeel-battery-demo/actions/workflows/android-ci.yml)
[![Release](https://img.shields.io/badge/Release-v0.2.0-2ea44f)](https://github.com/qfzlm/openfeel-battery-demo/releases/tag/v0.2.0)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)

一个针对弱水时砂 OpenFeel 系列耳机的 Android 电量读取工具。

> 自用为主：连接稳定、刷新快、界面简洁。

## 快速开始

1. 打开 Release 页面下载最新 APK：  
   [https://github.com/qfzlm/openfeel-battery-demo/releases](https://github.com/qfzlm/openfeel-battery-demo/releases)
2. 在手机上安装 APK 并授予蓝牙权限。

如果你是开发者，需要自行构建，请看下方“构建”章节与 `docs/DEV_QUICKSTART.md`。

## 功能

- 一键刷新：同时更新总电量与分电量。
- 总电量稳定兜底：标准 `180F / 2A19`。
- 分电量来自已验证私有帧：
  - `DD ?? 04 0C XX YY ZZ AA`
  - 左耳：`XX & 0x7F`
  - 右耳：`YY & 0x7F`
  - 充电仓：`ZZ & 0x7F`
- 临时断开后保留最后一次成功值。
- 支持导出日志到 `Download`（MediaStore）。

## 当前支持范围

- 目标：弱水时砂 OpenFeel（当前按已实测机型路径实现）
- 识别特征（实现细节）：
  - Manufacturer ID `0x0A0B`
  - 设备 MAC 前缀 `41:42`

本项目刻意保持在“已验证的个人设备路径”内，不做通用多设备平台。

## 正式版首页

首页只保留必要信息：

- 总电量
- 最近更新时间
- 当前连接状态
- 左耳 / 右耳 / 充电仓电量
- 刷新电量按钮
- 导出日志按钮

不展示调试面板、候选列表、GATT 原始明细。

## 构建

### 环境要求

- JDK 17
- Android SDK（`android-35` 与对应 build-tools）

### Windows 构建命令

```powershell
gradlew.bat :app:assembleDebug --no-daemon
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

开发者快速开始：

- `docs/DEV_QUICKSTART.md`

## 运行时权限

- Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`
- Android 11 及以下：BLE 扫描需要定位权限

## 缓存策略

- 总电量和分电量均保留最近一次成功值。
- 本次刷新失败不会清空旧值。
- 临时断开不会清空旧值。
- 仅在从未成功读取过时显示 `N/A` / `--`。

## 已知限制

- 设计上是单目标路径（不做多设备会话管理）。
- 充电状态语义暂不作为正式 UI 展示。
- 不发送未知私有写命令。

## 版本记录

- 见 GitHub Releases 与 `RELEASE_NOTES_v0.2.0.md`。

## 说明

这是基于实测与逆向线索整理出的个人工具项目。  
不同固件与硬件版本不保证完全兼容。
