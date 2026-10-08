# JARVIS 仓库结构说明

> 项目：JARVIS Android 端智能 AI 助手 | 包名：`com.jarvis.pineapple` | 技术栈：Kotlin + Jetpack Compose + Koin + SQLDelight + Ktor + LiteRT-LM

---

## 1. 顶层目录

```text
JARVIS/
├── app/                      # Android 应用主模块
│   ├── build.gradle.kts      # 模块构建脚本（依赖、签名、构建类型）
│   ├── proguard-rules.pro    # Release 混淆规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/jarvis/pineapple/   # Kotlin 源码（见第 2 节）
│       │   └── res/           # 资源文件（布局、图标、主题、字符串）
│       └── test/              # 单元测试
├── configs/
│   └── models/                # 4 个本地模型的 LiteRT-LM 配置
│       ├── gemma4_e2b_2.5b.yaml
│       ├── gemma4_12b.yaml
│       ├── lfm2.5_1.7b.yaml
│       ├── qwen3_0.6b.yaml
│       └── files/             # 下载的 .litertlm 模型文件（不入 Git）
├── docs/                      # 设计与交付文档
├── gradle/                    # Gradle Wrapper
├── scripts/                   # 辅助脚本
├── build.gradle.kts           # 根构建脚本
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── README.md
├── CHANGELOG.md
├── .gitignore
└── local.properties           # 本地 SDK 路径（不入 Git）
```

---

## 2. Kotlin 源码包结构（`app/src/main/java/com/jarvis/pineapple/`）

| 包 | 职责 | 关键文件 |
|----|------|----------|
| `(root)` | 应用入口 | [JarvisApp.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/JarvisApp.kt) |
| `personality` | 人格系统 | [Personality.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/personality/Personality.kt), [PersonalityRepository.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/personality/PersonalityRepository.kt) |
| `chat` | 对话引擎 | [ChatEngine.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/chat/ChatEngine.kt), [ChatMessage.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/chat/ChatMessage.kt) |
| `model` | 模型调度与本地推理 | [ModelScheduler.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/model/ModelScheduler.kt), [LocalModels.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/model/LocalModels.kt), [AiModel.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/model/AiModel.kt), [ApiModel.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/model/ApiModel.kt), [ServerModel.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/model/ServerModel.kt) |
| `wake` | 8 种唤醒方式 | [WakeEngine.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/wake/WakeEngine.kt), [VoiceWakeDetector.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/wake/VoiceWakeDetector.kt), [ShakeWakeDetector.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/wake/ShakeWakeDetector.kt), [BluetoothWakeDetector.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/wake/BluetoothWakeDetector.kt) |
| `vm` | 虚拟机管理 | [VmManager.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/vm/VmManager.kt) |
| `system` | 系统控制 | PhoneController / SmsController / CalendarController / NavigationController / SystemSettingsController / DeviceMonitor / CrossAppOperator |
| `service` | 保活与快捷入口 | [KeepAliveService.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/service/KeepAliveService.kt), [JarvisTileService.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/service/JarvisTileService.kt) |
| `receiver` | 广播接收 | [BootReceiver.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/receiver/BootReceiver.kt) |
| `assistant` | 语音助手服务 | JarvisRecognitionService / JarvisVoiceInteractionService |
| `speech` | TTS | [TtsManager.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/speech/TtsManager.kt) |
| `perception` | 环境感知 | EnvironmentPerception / ObjectDetector |
| `health` | 健康模块 | [HealthModules.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/health/HealthModules.kt) |
| `security` | 安全与加密 | [SecureStorage.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/security/SecureStorage.kt), [SecurityModules.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/security/SecurityModules.kt) |
| `smarthome` | 智能家居适配 | [SmartHomeAdapter.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/smarthome/SmartHomeAdapter.kt) |
| `autonomous` | 自主模块 | AutonomousModules / ReservedInterfaces |
| `accessibility` | 无障碍服务 | [JarvisAccessibilityService.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/accessibility/JarvisAccessibilityService.kt) |
| `db` | 数据库 | [DatabaseFactory.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/db/DatabaseFactory.kt) |
| `di` | 依赖注入 | [AppModule.kt](file:///workspace/app/src/main/java/com/jarvis/pineapple/di/AppModule.kt) |
| `ui` | Compose 界面 | MainActivity / chat / settings / vm / theme |

---

## 3. 模型文件放置约定

应用运行时，LiteRT-LM 模型文件需放置于应用私有目录：

```text
context.filesDir/models/
├── gemma4_e2b_2_5b.litertlm   # ~2.5 GB
├── gemma4_12b.litertlm        # ~6.7 GB (int8)
├── lfm2_5_1_7b.litertlm       # ~1.2 GB (实际为 LFM2.5-1.2B-Instruct int8)
└── qwen3_0_6b.litertlm        # ~586 MB
```

仓库 `configs/models/files/` 提供下载好的模型文件，实机测试时需手动拷贝到设备上的应用私有目录。

---

## 4. 构建产物

```text
app/build/outputs/apk/
├── debug/app-debug.apk        # 调试版（用于实机测试）
└── release/app-release.apk    # 发行版（仅在实机测试通过后产出）
```

---

*文档版本：v1.0 | 生成日期：2026-10-09*
