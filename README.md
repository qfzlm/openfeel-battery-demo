# 弱水时砂耳机电量

一个面向个人长期使用的极简 Android 工具，用于快速读取弱水时砂openfeel耳机总电量和分电量，目标是替代官方 App 的重流程和不稳定体验。

## 当前定位
- 只做一件事：稳定、快速地读取电量
- 优先保证连接链路和结果更新速度
- 不做多设备管理、不做复杂设置页

## 当前已验证能力
- 刷新时自动完成：连接 -> 读取总电量
- 自动启用私有回包通知并发送两条已验证命令，获取分电量帧
- 解析分电量帧
- 首页显示：总电量、最近更新时间、连接状态、左/右/充电仓
- 导出日志到 Download

## 使用
-直接在release下载使用

## 本地构建
1. 环境准备
   - JDK 17
   - Android SDK（`android-35`、对应 build-tools）
2. 配置 `local.properties`
   - `sdk.dir=你的 Android SDK 路径`
3. 使用项目自带 Wrapper 构建
   - Windows: `gradlew.bat -p E:\py\erji\TwsBatteryDemo :app:assembleDebug --no-daemon`
4. APK 路径
   - `app/build/outputs/apk/debug/app-debug.apk`

## 权限与运行要求
- 设备支持 BLE
- Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`
- Android 11 及以下：扫描需位置权限

## 已知限制
- 当前为单设备主线，不做多设备并行支持
- 充电状态语义暂未正式 UI 化（仅保留日志观察）
- 不发送除已验证两条命令以外的未知私有命令

## 免责声明
本项目为个人研究与自用工具，协议相关实现基于实测与逆向线索整理，不保证适配所有设备或固件版本。
