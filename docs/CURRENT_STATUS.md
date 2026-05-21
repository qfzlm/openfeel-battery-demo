# 当前状态

当前事实优先于 `docs/superpowers/specs` 里的历史 specs。那些 specs 是历史计划，不代表当前执行方向。

- 不恢复 App 内日志导出。
- 不进入 M3/M5 大重构。
- 不做后台周期刷新。
- 当前 BLE 行为以手动刷新 + 连接复用 + active request gate + Logcat 诊断为准。

## 当前项目定位

- 项目是手动触发的一次性 OpenFeel 电量读取工具。
- 用户点击刷新才主动读取。
- 允许复用已有 GATT 连接和 characteristic cache。
- 连接复用不等于后台刷新。
- 诊断日志通过 Android Studio Logcat / `adb logcat` 查看。

## 当前调试入口

- Android Studio Logcat 推荐过滤：
  - `level:DEBUG & package:com.budsbattery.app`
  - `level:DEBUG & tag:MainViewModel`
  - `level:DEBUG & tag:BleScannerManager`
  - `level:DEBUG & tag:OpenFeelGattSession`
- `adb logcat` 示例：
  ```powershell
  adb logcat -v time -s MainViewModel BleScannerManager OpenFeelGattSession
  ```
