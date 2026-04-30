# 开发快速开始（DEV QUICKSTART）

本文档用于本地开发、构建、测试与真机安装。

## 1. 环境准备

- JDK 17
- Android SDK（建议包含 `android-35`）
- Windows 下使用 `gradlew.bat`

可选环境变量示例：

```powershell
$env:JAVA_HOME="C:\Program Files\Zulu\zulu-17"
$env:ANDROID_SDK_ROOT="E:\Android\Sdk"
```

## 2. 拉取与构建

在项目根目录执行：

```powershell
gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 3. 单元测试

```powershell
gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

## 4. 可选检查

```powershell
gradlew.bat :app:lintDebug --no-daemon --console=plain
```

## 5. 安装到设备（可选）

确保设备已连接并开启 USB 调试：

```powershell
gradlew.bat :app:installDebug --no-daemon --console=plain
```

## 6. 真机验证建议

最小验证流程：

1. 打开 App，点击“刷新电量”。
2. 观察总电量与分电量是否更新。
3. 导出日志并检查关键字：
   - `refresh_pipeline_start`
   - `battery_read_result`
   - `f2_raw_notify`
   - `split_040c_parsed`
   - `refresh_pipeline_summary`

## 7. 日志导出

- 导出入口：App 内“导出日志”按钮。
- 导出位置：Download（MediaStore）。
- 排障关键词见：[`troubleshooting.md`](troubleshooting.md)

## 8. 维护原则

- 修改 BLE 时序前先建立日志基线。
- 优先小步可回退改动。
- 协议未验证部分不进入正式逻辑。
