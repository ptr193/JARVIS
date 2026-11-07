# Requirements Document

## Introduction

本轮按用户实机反馈，把 JARVIS Android 0.0.1 从「模块已接、体验未达标」对齐到规格书 3.1 / 3.3、计划书分区 3/4/11 与补充规格 REQ-02/05/09。只修本轮确定的体验与运行时缺口；科幻预留、无公开 API 的 GPU 占用、虚拟机图形桌面不纳入本轮。

## Glossary

- **System**：JARVIS Android 应用（`com.jarvis.pineapple`）
- **十标签**：规格书 3.3 的代理、服务、工具、虚拟机、状态、仓库、连接设备、语言语音、日志、通用
- **按需授权**：危险权限仅在用户点击对应功能后弹出系统对话框
- **RESERVED**：规格允许仅保留接口的能力

## Requirements

### Requirement 1 设置十标签可理解、可滑动、可记住

**User Story:** AS 用户, I want 设置页每个标签有说明且可横滑并记住位置, so that 我能找到规格中的设置项

#### Acceptance Criteria

1. WHEN 用户打开设置, the System SHALL 展示规格书 3.3 的十个标签，每个标签附带一行功能说明
2. WHEN 用户左右滑动设置内容区, the System SHALL 切换到相邻标签
3. WHEN 用户离开设置后再进入, the System SHALL 恢复上次选中的标签索引
4. WHEN 用户打开「通用」, the System SHALL 提供「一键设为默认 AI 助手」按钮，点击后跳转系统默认助手设置页

### Requirement 2 状态与设备监测实时

**User Story:** AS 用户, I want 状态页自动刷新 CPU 与设备信息, so that 我不必手动点刷新

#### Acceptance Criteria

1. WHILE 状态标签可见, the System SHALL 周期性读取 CPU 使用率、内存、温度、电池并更新界面
2. IF `/proc/stat` 不可读, the System SHALL 显示中文原因「无法读取 CPU 使用率」
3. WHILE 连接设备标签可见, the System SHALL 按固定间隔刷新已连接/可发现设备列表

### Requirement 3 主界面与主题

**User Story:** AS 用户, I want 聊天三区布局与可生效的主题色, so that 界面符合规格 3.1 与 4.3

#### Acceptance Criteria

1. WHEN 用户停留在聊天页, the System SHALL 使用规格 3.1 顶部（虚拟机 / 朗读 / 设置）、中部对话、底部（模型 / 上传 / 输入 / 发送）
2. WHEN 用户打开虚拟机或设置, the System SHALL 让内容紧贴系统状态栏下方，避免额外空白条
3. WHEN 用户在通用页选择预设主题色, the System SHALL 把该颜色应用到 Material 主色并立即重绘
4. WHEN 用户从设置切到聊天再回到设置, the System SHALL 保持上次标签，避免回到默认第一页

### Requirement 4 权限与首次运行

**User Story:** AS 用户, I want 自己决定何时授权、何时安装模型, so that 应用不会静默占用权限或静默下载

#### Acceptance Criteria

1. WHEN 应用冷启动, the System SHALL 不批量弹出危险权限请求
2. WHEN 用户首次使用麦克风/电话/短信/日历/相机/定位, the System SHALL 先说明用途再调用系统权限对话框
3. WHEN 用户首次进入主界面且本地无可用模型, the System SHALL 展示模型安装向导，列出默认可选模型并等待用户确认后下载
4. IF 模型下载超时或失败, the System SHALL 显示中文错误并提供重试，保持对话可进入但推理失败原因明确

### Requirement 5 虚拟机发行版与 PRoot

**User Story:** AS 用户, I want 按清单下载发行版并用已配置的 PRoot 运行, so that 终端命令来自真实进程

#### Acceptance Criteria

1. WHEN 用户打开可下载发行版, the System SHALL 列出 Debian 12、Alpine 3.21、Debian 13、Ubuntu 24.04 LTS、Arch Linux、Void Linux 及其 x86_64 模拟条目，并标注体积
2. WHEN 用户首次打开虚拟机, the System SHALL 使用已随包或已缓存的 PRoot，无需额外「初始化」步骤即可检测就绪状态
3. IF 发行版下载或解压失败, the System SHALL 返回中文错误并允许重试或手动导入
4. IF 用户选择 x86_64 模拟且设备缺少模拟组件, the System SHALL 提示「当前设备无法模拟 x86_64」

### Requirement 6 默认男声与聊天记录

**User Story:** AS 用户, I want 钢铁侠中文男声和可管理的对话记录, so that 语音与历史符合人格与多轮规格

#### Acceptance Criteria

1. WHEN 语音合成使用默认声线, the System SHALL 使用男性、《钢铁侠》中文版 JARVIS 描述的声线；源不可达时降级系统中文 TTS 并提示
2. WHEN 用户打开聊天历史, the System SHALL 列出已持久化对话，支持切换、新建、删除
3. WHEN 用户发送消息, the System SHALL 把该轮消息写入当前对话文件

### Requirement 7 本轮范围边界

**User Story:** AS 维护者, I want 明确不做的项, so that 实现不扩散到无法验收的能力

#### Acceptance Criteria

1. WHEN 规划本轮任务, the System 的实现范围 SHALL 覆盖 Requirement 1–6 与回归打包
2. WHEN 涉及 GPU 占用率、虚拟机 Wayland 桌面、关机语音唤醒、Release 包, the System SHALL 保持现有接口或文档说明，不新增假成功路径
