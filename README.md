# TWS Battery Demo (Android)

## 项目用途
这是一个用于扫描 BLE 广播并解析 TWS 耳机电量信息的 Android Kotlin 示例项目。当前目标是复现“小米系耳机广播电量显示”链路。

## 当前能力
- 扫描 BLE 广播
- 从 service data 提取并解析电量字段
- 界面显示 left/right/case/extraStatus、原始 hex、更新时间
- 协议层为可扩展路由结构（当前仅接入 XiaomiFastConnectParser）

## 本地构建步骤
1. 准备环境
   - JDK 17（示例：Zulu 17）
   - Android SDK（包含 platform-tools、platforms;android-35、build-tools;35.0.0）
2. 配置 `local.properties`
   - `sdk.dir=E:\\Android\\Sdk`（按本机实际路径调整）
3. 使用项目内 Wrapper 构建
   - Windows: `gradlew.bat -p E:\py\erji\TwsBatteryDemo :app:assembleDebug`
4. 产物路径
   - `app/build/outputs/apk/debug/app-debug.apk`

## 运行要求
- Android 设备支持 BLE
- Android 12+ 需授予 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`
- Android 11 及以下需位置权限以进行 BLE 扫描

## 协议说明（当前）
- 当前仅支持 `XiaomiFastConnectParser`
- 已保留协议分发结构，后续可扩展其他品牌解析器

## 已知限制
- 仅实现广播解析链路，不包含主动厂商命令查询
- 解析规则依赖当前逆向线索（固定偏移/type 过滤）
- 列表按 MAC 更新，随机地址场景可能影响稳定归并
