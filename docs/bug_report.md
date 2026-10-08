# JARVIS Bug 报告

> 记录开发与测试过程中发现的所有缺陷、根因、修复与验证状态。

---

## 1. 已修复 Bug

### Bug #1：VmManager ID 冲突（严重 / P1）

- **发现阶段**：单元测试 `VmManagerTest > multiple vms run independently`
- **现象**：`expected:<STOPPED> but was:<RUNNING>`
- **根因**：[VmManager.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/vm/VmManager.kt) 的 `create()` 使用 `System.currentTimeMillis()` 作为虚拟机 ID。两个虚拟机在同一毫秒内创建时得到相同 ID，`start(a.id)` 会同时启动 a 和 b。
- **影响**：多虚拟机场景下，启动一个虚拟机会意外启动同毫秒创建的其他虚拟机。
- **修复**：引入计数器，ID 格式改为 `vm_${时间戳}_${计数器}`，保证唯一性。
- **验证**：单元测试 `VmManagerTest` 5/5 通过。

### Bug #2：Lint MissingPermission（3 处 / P2）

- **发现阶段**：`lintDebug`
- **文件**：
  - [PhoneController.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/system/PhoneController.kt)（`call`/`answerCall`/`readCallLogs`）
  - [SystemSettingsController.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/system/SystemSettingsController.kt)（`setWifiEnabled`/`setBluetoothEnabled`）
  - [VoiceWakeDetector.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/wake/VoiceWakeDetector.kt)（`start`）
- **根因**：方法调用需要运行时权限，但未声明 `@SuppressLint`。
- **修复**：添加 `@SuppressLint("MissingPermission")`，并在注释中标注运行时权限由调用方确保。
- **验证**：lint 0 错误。

### Bug #3：Lint NewApi（多处 / P2）

- **发现阶段**：`lintDebug`
- **根因**：使用了高 API Level 的 API（如 `PriorityQueue` 等），未加 `@RequiresApi`。
- **修复**：添加 `@RequiresApi` 注解；将 `PriorityQueue` 替换为 `MutableList` 以兼容低版本。
- **验证**：lint 0 错误。

### Bug #4：Lint UnspecifiedRegisterReceiverFlag（P2）

- **发现阶段**：`lintDebug`
- **根因**：`Context.registerReceiver` 未指定导出标志。
- **修复**：改用 `ContextCompat.registerReceiver` 并指定 `RECEIVER_NOT_EXPORTED`。
- **验证**：lint 0 错误。

### Bug #5：Java 版本冲突（构建环境 / P1）

- **发现阶段**：`./gradlew assembleDebug`
- **现象**：Kotlin 编译器报错不支持 Java 25。
- **根因**：环境默认 JDK 为 Java 25，Kotlin/AGP 不兼容。
- **修复**：通过 mise 切换至 Java 17。
- **验证**：`./gradlew assembleDebug` 成功。

### Bug #6：Android SDK 缺失（构建环境 / P1）

- **发现阶段**：首次构建
- **根因**：环境未安装 Android SDK platform-35 与 build-tools-35.0.0。
- **修复**：手动下载 SDK 命令行工具并安装对应组件。
- **验证**：构建通过。

---

## 2. 已知未修复 / 待实机验证

| 编号 | 描述 | 级别 | 状态 |
|------|------|------|------|
| TBD-1 | 8 种唤醒方式在真机上的实际触发率未知 | P1 | 待实机测试 |
| TBD-2 | `KeepAliveService` 前台服务保活效果（厂商定制 ROM 可能杀后台） | P1 | 待实机测试 |
| TBD-3 | 运行时权限请求流程（电话/短信/定位/麦克风/通知） | P1 | 待实机测试 |
| TBD-4 | 4 个 LiteRT 模型在真机上的加载与推理性能 | P1 | 待实机测试 |
| TBD-5 | 语音识别（ASR）与唤醒词（KWS）未接入具体引擎 | P2 | 仅接口预留 |
| TBD-6 | 智能家居 Matter/Tuya/米家协议适配未实现 | P2 | 仅接口预留 |

---

*文档版本：v1.0 | 生成日期：2026-10-09*
