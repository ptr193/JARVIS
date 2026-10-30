# Requirements Document

## Introduction

本需求将现有 JARVIS Android 工程对齐《JARVIS_产品规格书》与《JARVIS补充内容规格书》，消除占位实现，形成可测试、可侧载的 0.0.1 Debug 交付。

## Glossary

- **System**：JARVIS Android 应用（`com.jarvis.pineapple`）
- **RESERVED**：规格书允许仅保留接口的科幻、违法或无硬件能力
- **真实实现**：有平台 API 或文件/进程结果支撑，失败时返回明确错误
- **占位**：空函数体、假进度条、硬编码成功值、无调用方的死代码冒充完成

## Requirements

### Requirement 1 仓库与流程

**User Story:** AS 维护者, I want 源码与计划在 main 上可追踪, so that 每步完成后可审查

#### Acceptance Criteria

1. WHEN 阶段任务完成, the System 的仓库 SHALL 仅更新 `main` 分支
2. WHEN 出现新的补充需求, the System 的计划文档 SHALL 先追加条目再实现
3. IF 实机测试未完成, the System SHALL 只允许 Debug 构建作为交付

### Requirement 2 构建与测试

**User Story:** AS 开发者, I want 每次改动后跑测试, so that 回归可被发现

#### Acceptance Criteria

1. WHEN 阶段 1 环境就绪, the System SHALL 能通过 `assembleDebug`
2. WHEN 任意实现阶段结束, the System SHALL 能通过 `testDebugUnitTest`
3. IF 测试失败, the System SHALL 保持该阶段未完成状态直到修复

### Requirement 3 对话与人格

**User Story:** AS 用户, I want 与中文 JARVIS 多轮对话, so that 助手表现符合灵魂描述

#### Acceptance Criteria

1. WHEN 用户发送文本, the System SHALL 注入人格 prompt 并走模型调度
2. WHILE 无可用模型, the System SHALL 向用户展示加载/配置失败原因
3. WHEN 用户修改人格, the System SHALL 持久化并在后续对话生效

### Requirement 4 模型

**User Story:** AS 用户, I want 按优先级使用自有、本地、API、服务器模型, so that 推理来源可控

#### Acceptance Criteria

1. WHEN 选择模型, the System SHALL 按 用户自有 > LiteRT > API > 服务器 调度
2. WHEN 导入本地模型文件, the System SHALL 识别格式并在不支持时给出格式清单
3. IF 模型文件缺失, the System SHALL 拒绝假装推理成功

### Requirement 5 唤醒与保活

**User Story:** AS 用户, I want 多种方式唤醒并保持后台存活, so that 助手可随时响应

#### Acceptance Criteria

1. WHEN 任一已授权唤醒源触发, the System SHALL 进入对话/语音会话
2. WHILE 前台服务运行, the System SHALL 展示常驻通知
3. IF 所需权限未授予, the System SHALL 提示并保持其他唤醒源可用

### Requirement 6 虚拟机

**User Story:** AS 用户, I want 在手机上管理 Linux 环境, so that 可安装发行版并执行命令

#### Acceptance Criteria

1. WHEN PRoot 与 rootfs 可用, the System SHALL 用真实进程执行命令
2. WHEN 发行版下载失败, the System SHALL 返回错误并支持重试或手动导入
3. IF 后端不可用, the System SHALL 提示缺失组件且不返回伪造 shell 输出

### Requirement 7 系统与设备能力

**User Story:** AS 用户, I want 电话短信日程导航与家居控制, so that 助手能操作设备

#### Acceptance Criteria

1. WHEN 权限已授予, the System SHALL 发出对应系统 Intent 或 Provider 写入
2. IF 权限或设备缺失, the System SHALL 提示「未授予权限」或「未检测到设备」
3. WHEN 提醒到期, the System SHALL 通过已注册 Receiver 发出通知

### Requirement 8 安全、健康、感知、自主

**User Story:** AS 用户, I want 安全与感知能力可从设置触达, so that 功能不是死代码

#### Acceptance Criteria

1. WHEN 用户打开对应设置页, the System SHALL 调用真实 Manager 而非硬编码列表
2. WHILE 设备管理员未激活, the System SHALL 拒绝锁定与擦除并给出引导
3. WHEN 规格标记 RESERVED, the System SHALL 暴露接口并提示不受支持

### Requirement 9 REQ-05 禁止占位

**User Story:** AS 验收者, I want 每个声明功能都可调用或明确失败, so that 接口即插即用

#### Acceptance Criteria

1. WHEN 审查公开 API, the System SHALL 在成功路径使用真实数据源
2. IF 外部硬件未连接, the System SHALL 返回可测试的失败结果
3. WHEN 阶段完成, the System 的该模块 SHALL 不包含空 `onClick` 与假进度条
