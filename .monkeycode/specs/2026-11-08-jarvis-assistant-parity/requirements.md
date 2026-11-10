# Requirements Document

## Introduction

按市面默认数字助手（YoYo / 小爱 / 小艺 / Siri）的共同路径，把「不打开应用即可用」拆成 A1–C1。A1 已完成。本轮落地 A2 overlay 回答、A3 识屏、B1 建议卡片、B2 默认助手引导、C1 多模态入口。

## Glossary

- **System**：JARVIS Android 应用（`com.jarvis.pineapple`）
- **overlay**：`VoiceInteractionSession` 内容视图，含 JARVIS 标识与声纹条
- **A1**：overlay 听写（ASR + RMS + 中文失败）
- **A2**：overlay 回答（ChatEngine + TTS）
- **A3**：overlay 识屏（AssistStructure）
- **B1**：主动建议通知卡片
- **B2**：通用页默认助手状态检测
- **C1**：overlay / 建议卡片触发识物与扫文档

## Requirements

### Requirement 1 Overlay 听写（A1）

**User Story:** AS 已把 JARVIS 设为默认数字助手的用户, I want 长按 Home 后直接说话并看到识别文字, so that 我不必打开应用

#### Acceptance Criteria

1. WHEN 用户长按 Home 且已授予麦克风权限, the System SHALL 在 overlay 内启动语音识别，并展示「正在聆听，请说话」
2. WHILE 用户说话, the System SHALL 用识别回调的 RMS 驱动声纹条，并用部分结果更新 overlay 文案
3. WHEN 识别完成, the System SHALL 在 overlay 展示最终识别文本，禁止拉起主界面
4. IF 未授予麦克风权限, the System SHALL 显示「未授予麦克风权限」，禁止假装正在聆听
5. IF 语音识别不可用或识别失败, the System SHALL 显示中文错误，禁止伪造识别结果

### Requirement 2 Overlay 回答（A2）

**User Story:** AS 已把 JARVIS 设为默认数字助手的用户, I want 说完话后直接听到回答, so that 我不必打开主界面

#### Acceptance Criteria

1. WHEN overlay 得到非空识别文本, the System SHALL 把文本交给 ChatEngine 并在 overlay 展示回复
2. WHEN ChatEngine 返回非空回复, the System SHALL 用 SpeechManager 播报，禁止拉起主界面
3. IF 对话引擎不可用或生成失败, the System SHALL 显示中文错误，禁止伪造回答

### Requirement 3 Overlay 识屏（A3）

**User Story:** AS 长按 Home 的用户, I want JARVIS 读到当前屏幕文字, so that 我可以针对眼前内容提问

#### Acceptance Criteria

1. WHEN 系统提供 AssistStructure, the System SHALL 提取可见文本作为 overlay 回答上下文
2. IF AssistStructure 为空或无文本, the System SHALL 按无屏幕上下文继续听写与回答，禁止伪造屏幕内容

### Requirement 4 主动建议卡片（B1）

**User Story:** AS 未打开应用的用户, I want 收到锁屏或通知栏建议, so that JARVIS 能主动提醒

#### Acceptance Criteria

1. WHEN 保活循环执行自主任务, the System SHALL 调用 generateContextualSuggestions 并推送通知卡片
2. IF 当日相同建议已推送或列表为空, the System SHALL 跳过，禁止空通知或假成功

### Requirement 5 默认助手引导（B2）

**User Story:** AS 打开通用设置的用户, I want 看到 JARVIS 是否为默认助手, so that 我能一键进入系统设置

#### Acceptance Criteria

1. WHEN 通用页加载, the System SHALL 检测 voice_interaction_service 是否指向本应用
2. IF 尚未设为默认助手, the System SHALL 展示「尚未设为默认数字助手」并提供系统设置入口

### Requirement 6 多模态入口（C1）

**User Story:** AS 使用 overlay 或建议卡片的用户, I want 直接识物或扫文档, so that 我不必先打开主界面找相机页

#### Acceptance Criteria

1. WHEN 用户在 overlay 点「识物」或「扫文档」, the System SHALL 打开相机采集页并走 CameraCaptureSession
2. WHEN 用户点击建议卡片上的识物或扫文档动作, the System SHALL 打开同一采集页
3. IF 无相机权限、未拍到图或引擎不可用, the System SHALL 显示中文错误，禁止假装识别成功
