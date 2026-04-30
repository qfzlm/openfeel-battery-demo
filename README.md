# 弱水时砂 OpenFeel 电量（Android）

这是一个针对弱水时砂 OpenFeel 系列耳机的 Android 电量读取工具，面向个人长期自用，目标是稳定读取总电量与左右耳/充电仓分电量。

## 项目定位

- 面向弱水时砂 OpenFeel 系列的已验证个人设备路径。
- 当前按实测设备定向优化，不做通用多设备平台。
- 优先保留已验证链路，不猜测未验证协议。

## 功能概览

- 总电量显示（标准 Battery Service 180F / 2A19）
- 左耳 / 右耳 / 充电仓电量显示（私有 04 0C 帧）
- 最近更新时间与连接状态显示
- 刷新电量按钮
- 导出日志按钮（导出到 Download）

## 当前支持范围

- 目标设备：弱水时砂 OpenFeel 系列耳机
- 当前实测主设备：`41:42:D3:16:6F:68`
- 设备识别特征（实现细节）：
  - Manufacturer ID: `0x0A0B`
  - MAC 前缀：`41:42`

> 当前实现是 OpenFeel 已验证个人设备路径，不保证覆盖所有批次或固件。

## 快速开始

### 环境要求

- JDK 17
- Android SDK（建议包含 `android-35` 与对应 build-tools）

### 本地构建 Debug APK

```powershell
gradlew.bat :app:assembleDebug --no-daemon
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## BLE 刷新链路（简版）

`refresh -> reuse/connect -> discover -> read 2A19 -> enable F2 notify -> write F1 -> receive F2 -> parse 04 0C`

说明：
- 总电量由 `2A19` 提供稳定兜底。
- 分电量来自 F2 通知中的 `DD ?? 04 0C XX YY ZZ AA`。

## 日志与导出

- App 内支持导出日志到 Download（MediaStore 路径）。
- 建议在真机验证时保留导出日志，用于对比刷新时序与解析结果。
- 排障关键词与路径见：[`docs/troubleshooting.md`](docs/troubleshooting.md)

## 近期工程改进（已完成）

- Parser 单元测试
- 结构化日志基线
- 协程化调度，替换 Thread/sleep
- Pipeline shadow state
- Pipeline shadow 一致性对照日志
- 停用无用后台 BLE 扫描

## 已知限制

- 当前实现是 OpenFeel 已验证个人设备路径，不保证覆盖所有批次或固件。
- 不是通用蓝牙耳机电量平台。
- `left=0` 当前按设备上报值显示，后续可单独定义未知态策略。
- 后台扫描入口已停用，刷新链路直接使用目标 MAC。
- 不猜测未验证协议，不自动发送未知命令。

## 开发文档

- 架构说明：[`docs/architecture.md`](docs/architecture.md)
- 开发快速开始：[`docs/DEV_QUICKSTART.md`](docs/DEV_QUICKSTART.md)
- 排障指南：[`docs/troubleshooting.md`](docs/troubleshooting.md)

## 许可证

MIT，见 [`LICENSE`](LICENSE)。
