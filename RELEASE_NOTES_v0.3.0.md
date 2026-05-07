# v0.3.0 发布说明

本版本是 OpenFeel 电量工具的 **刷新稳定性基线**。

## 关键提交

- `3bfb45a` BLE baseline tests and coroutine scheduling
- `0434c1d` GATT pipeline shadow logging
- `11afea0` pipeline state shadow consistency log
- `f7325ca` disabled unused background BLE scan
- `a170c9f` project documentation refresh
- `cc027d5` refresh UI and split write timing stabilization

## 主要修复

- 增加 **battery read gate**：2A19 read 完成或 500ms 超时后，再放行 split F1 写入。
- 增加 UI `isRefreshing` 防回弹，避免刷新完成后被后续状态覆盖回“读取中”。
- 停用无用后台 BLE 扫描入口，减少空转耗电。
- 保留 parser 单测与结构化日志基线，便于回归验证。

## 已验证场景

- 冷启动立即刷新
- UI timeout 后后续回包
- 分电量解析
- pipeline shadow 一致性

## 已知限制

- 当前仍是 OpenFeel 已验证个人设备路径。
- `left=0` 仍按设备上报值显示。
- 不猜测未验证协议语义。
- M3b-step2 和 M3c 暂缓。

