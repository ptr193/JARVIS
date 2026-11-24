# Changelog

## Unreleased (2026-11-24)

### Added
- 附件改为「先暂存、后发送」：选择图片/文件后只进入输入框上方的待发送区，用户可继续输入文字，按发送才随消息一起发出
- 支持按绝对路径添加附件（如 `/storage/emulated/0/DCIM/Camera/IMG_001.jpg`），便于让 AI 在文件管理器中定位后再手动附加；同时声明 `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO` 与 `MANAGE_EXTERNAL_STORAGE`
- 新增国产开源模型：Qwen 2.5 1.5B Instruct、DeepSeek R1 Distill Qwen 1.5B（均含完整大小与 SHA-256），并补齐能力画像

### Changed
- HuggingFace 模型下载改为「国内优先」：ModelScope（魔搭）→ hf-mirror → HuggingFace 直连，解决国内网络下 Qwen 等模型“下不了”的问题
- GitHub 资产（PRoot/Debian 等）下载改走 ghproxy 优先
- 虚拟机镜像下载参照 Haven 增强：断点续传、失败自动重试 3 次、固定分片文件、60s 读超时
- Arch Linux 改用清华镜像为主源（官方源国内常 502），并修正镜像体积

### Fixed
- 滚动发行版（Arch 等）上游无校验和时不再直接拒绝下载，改为照常下载并明确标注“未校验”
- 修复模型下载分片位置无效（HTTP 416）时未重置分片、错误信息缺失的问题

## Unreleased (2026-11-23)

### Fixed
- 本地指令（系统控制/翻译/root/虚拟机）改为仅以 `/` 或 `／` 显式触发，普通对话不再被硬编码关键词劫持；无模型时普通聊天返回明确无模型提示
- 压力测试与 UI 快捷指令同步改为显式前缀，新增指令入口与无模型回归用例
- 语音通道（ASR 文本）禁用本地硬编码指令：`ChatEngine.sendUserMessage` 新增 `allowLocalCommands` 参数，语音入口传 `false`，即使 ASR 偶然输出带 `/` 前缀的文本也不触发 root/翻译/虚拟机/系统快捷指令，统一走模型或无模型提示
- 修复 VNC 写失败原因被读循环以“连接已关闭”覆盖的高负载竞态：`VncClient.writeEvent` 改为先 `notifyDisconnected(reason)` 再关闭 socket

## Unreleased (2026-10-30)

### Added
- 仓库从压缩包导入完整 Android 工程（丢弃 `root/jarvis-final` 旧副本）
- `docs/实施计划.md`：分阶段做什么、交付什么、完成标准、测试命令
- `.monkeycode/specs/2026-10-30-jarvis-completion/` 需求、设计、任务列表

### Changed
- README 指向实施计划；阅读顺序文档修正

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
