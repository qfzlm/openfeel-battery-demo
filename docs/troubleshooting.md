# 排障指南（Troubleshooting）

本文档列出常见现象、关键日志关键词与排查入口。

## 1. 优先检查点

- 蓝牙是否开启
- 权限是否授予（Android 12+ 需要 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`）
- 目标设备是否处于可连接状态

## 2. 刷新主链路关键词

按顺序关注：

1. `refresh_pipeline_start`
2. `refresh_reuse_session=true/false`
3. `connection_state` / `discover_services`
4. `capability_detected`
5. `battery_read_result`
6. `f2_notify_enable_start` / `f2_notify_enable_result`
7. `f1_write_1_queue` / `f1_write_2_queue`
8. `f2_raw_notify`
9. `split_040c_parsed`
10. `refresh_pipeline_summary`
11. `refresh_ui_complete`

## 3. 常见问题与方向

### A. 刷新按钮点击无反应

先查：
- `refresh_ignored reason=in_flight`（是否被防抖拦截）
- 权限/蓝牙相关错误日志

### B. 只有总电量，没有分电量

先查：
- 是否出现 `f2_raw_notify`
- 是否出现 `split_040c_parsed`
- `refresh_pipeline_summary` 中 `frameCount` 与 `has040c`

### C. 刷新结束太快但分电量稍后才到

这是允许行为：
- UI 可先按短超时完成
- pipeline 观察窗口仍可能继续，后续到帧仍可更新

### D. 连接反复重建

关注：
- `refresh_reuse_session=false` 的 `reason=...`
- 是否每次都重新 `discover_services`

## 4. Shadow 状态对照日志（M3）

可关注：
- `pipeline_state from=... to=...`
- `pipeline_state_shadow MATCH/MISMATCH`

用于比对 shadow 状态与旧变量一致性，不代表业务判断已迁移。

## 5. 导出日志

- 先在 App 内导出日志到 Download。
- 用同一轮刷新日志对齐 `refreshId` / `requestId` 做分析。

## 6. 不建议的排障方式

- 不要改命令字节做盲测。
- 不要把未验证协议语义直接写进 UI。
- 不要在缺基线日志时直接重构关键时序代码。
