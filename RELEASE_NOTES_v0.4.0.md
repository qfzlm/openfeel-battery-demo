# v0.4.0 发布说明

本版本是 OpenFeel 电量工具的 **manual refresh checkpoint**。

## 核心变更

- 引入 active request gate，分电量 split 帧只在当前有效刷新请求窗口内生效。
- 可见刷新状态不再等待完整 observe window 结束，电量数据到达后即可结束可见刷新。
- 移除应用内日志查看与导出功能。
- 诊断入口统一为 Android Studio Logcat / `adb logcat`。
- 常用日志 tag：`MainViewModel`、`BleScannerManager`、`OpenFeelGattSession`。
- README 与 docs 已同步到当前行为。

## 行为边界

- 允许连接复用（GATT + characteristic cache），但连接复用不等于后台刷新。
- 不做后台周期刷新。
- 历史 M3/M5 specs 仅作历史参考，不是当前执行方向。
