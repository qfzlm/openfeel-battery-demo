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
