# JARVIS — Android 端智能 AI 助手

> 基于《JARVIS_产品计划书》《JARVIS_产品规格书》《JARVIS_人格灵魂描述》实现的 Android 端智能 AI 助手，支持人格化对话、8 种唤醒方式、虚拟机管理、系统控制、端侧 LLM 推理等核心功能。

---

## 功能特性

- **人格系统**：可配置名称、声线、system prompt，持久化存储
- **对话引擎**：多轮上下文管理、流式回复
- **8 种唤醒方式**：语音、电源键连按、通知栏、摇一摇、蓝牙、开机自启、应用内按钮、无障碍快捷方式
- **虚拟机管理**：多虚拟机创建/启停/删除 GUI
- **系统控制**：电话、短信、日程、导航、系统设置
- **模型调度**：用户自有模型 > LiteRT 本地模型 > API 模型 > 服务器模型
- **端侧推理**：接入 LiteRT-LM，支持 Gemma 4 / LFM 2.5 / Qwen 3 等本地模型
- **保活**：前台服务 + 电池优化白名单引导

---

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose |
| DI | Koin |
| 数据库 | SQLDelight |
| 网络 | Ktor |
| 端侧 LLM | LiteRT-LM（`com.google.ai.litert:litert-lm`） |
| 构建 | Gradle 8.14.5 + AGP 8.7.3 |
| 最低 SDK | 26 |
| 目标 SDK | 35 |

---

## 构建

### 环境要求

- JDK 17
- Android SDK（platform-35, build-tools-35.0.0）
- 网络（首次构建需下载依赖）

### 命令

```bash
# Debug
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release（需先完成实机测试，见 docs/process_compliance.md）
./gradlew assembleRelease

# 单元测试
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

---

## 模型适配

本项目通过 LiteRT-LM 在端侧运行大模型。已适配 4 个模型，配置文件位于 `configs/models/`：

| 模型 | 配置文件 | 模型文件 | 大小 |
|------|----------|----------|------|
| Gemma 4 E2B 2.5B | `gemma4_e2b_2.5b.yaml` | `gemma4_e2b_2_5b.litertlm` | ~2.5 GB |
| Gemma 4 12B | `gemma4_12b.yaml` | `gemma4_12b.litertlm` | ~6.7 GB (int8) |
| LFM 2.5 1.2B Instruct | `lfm2.5_1.7b.yaml` | `lfm2_5_1_7b.litertlm` | ~1.2 GB |
| Qwen 3 0.6B | `qwen3_0.6b.yaml` | `qwen3_0_6b.litertlm` | ~586 MB |

> 注：原计划指定 LFM2.5 1.7B，但 Liquid AI 官方 LFM2.5 系列无 1.7B 规格，已替换为最接近的 LFM2.5-1.2B-Instruct (int8)。

### 模型文件放置

将 `.litertlm` 文件放入应用私有目录：

```text
context.filesDir/models/
├── gemma4_e2b_2_5b.litertlm
├── gemma4_12b.litertlm
├── lfm2_5_1_7b.litertlm
└── qwen3_0_6b.litertlm
```

模型文件可从 Hugging Face `litert-community` 组织下载（见各 YAML 中的 `download_url`）。

---

## 实机测试

Debug APK 安装后，请按 [docs/release_checklist.md](docs/release_checklist.md) 第 2 节逐项验证，重点包括：

1. 8 种唤醒方式的实际触发
2. 前台服务保活效果
3. 运行时权限请求流程
4. 4 个模型的加载与推理冒烟测试

---

## 流程合规

本项目严格遵循《JARVIS_补充计划.md》规定的流程：

```text
实现 → 单元测试 → 集成测试 → Debug 打包 → 实机测试 → Bug 修复 → 回归测试 → Release 打包
```

Release 打包仅在实机测试通过、所有门槛满足后进行。详见 [docs/process_compliance.md](docs/process_compliance.md)。

---

## 文档索引

- [产品规格书](docs/JARVIS_产品规格书.md)
- [人格灵魂描述](docs/JARVIS_人格灵魂描述.md)
- [需求追踪矩阵](docs/requirements_traceability.md)
- [仓库结构](docs/repo_structure.md)
- [阶段一测试报告](docs/test_report_stage1.md)
- [Bug 报告](docs/bug_report.md)
- [回归测试报告](docs/regression_test_report.md)
- [流程合规记录](docs/process_compliance.md)
- [Release 检查清单](docs/release_checklist.md)

---

## License

参见各模型对应许可证（Gemma / LFM / Qwen）。
