# Changelog

## v0.0.1 (2026-10-09)

### Added
- 核心人格系统（Personality + PersonalityRepository）
- 对话引擎（ChatEngine 多轮上下文 + 流式回复）
- 8 种唤醒方式统一分发（WakeEngine）
- 虚拟机管理（VmManager + VirtualMachineScreen）
- 系统控制（电话/短信/日程/导航/系统设置）
- 模型调度层（用户自有 > LiteRT > API > 服务器）
- LiteRT-LM 本地推理接入（Gemma 4 / LFM 2.5 / Qwen 3）
- 前台保活服务（KeepAliveService）
- 智能家居适配接口（SmartHomeAdapter）
- 安全模块（SecureStorage + SecurityAuditor）
- 健康模块（疲劳检测/用药/生命体征）
- 环境感知与目标检测
- Compose UI（对话/设置/虚拟机）

### Fixed
- VmManager ID 冲突（同毫秒创建的虚拟机 ID 重复）
- Lint 33 处错误 → 0（MissingPermission / NewApi / RegisterReceiverFlag）
- Java 版本冲突（切换至 Java 17）
- Android SDK 缺失（安装 platform-35 + build-tools-35.0.0）

### Changed
- 模型文件格式从 `.tflite` 更正为 `.litertlm`（LiteRT-LM 实际格式）
- LFM2.5 1.7B 替换为 LFM2.5-1.2B-Instruct int8（原规格不存在）

### Notes
- Release APK 标记为未验证，待实机测试通过后重新打包
- 模型文件不入 Git，需从 Hugging Face 下载
