# Requirements Document

## Introduction

按市面默认数字助手（YoYo / 小爱 / 小艺 / Siri）的共同路径，把「不打开应用即可用」拆成多个小任务。本轮只做 A1：长按 Home overlay 真实听写。A2 回答、A3 识屏、B1 建议卡片不纳入本轮。

## Glossary

- **System**：JARVIS Android 应用（`com.jarvis.pineapple`）
- **overlay**：`VoiceInteractionSession` 内容视图，含 JARVIS 标识与声纹条
- **A1**：overlay 听写（ASR + RMS + 中文失败）

## Requirements

### Requirement 1 Overlay 听写（A1）

**User Story:** AS 已把 JARVIS 设为默认数字助手的用户, I want 长按 Home 后直接说话并看到识别文字, so that 我不必打开应用

#### Acceptance Criteria

1. WHEN 用户长按 Home 且已授予麦克风权限, the System SHALL 在 overlay 内启动语音识别，并展示「正在聆听，请说话」
2. WHILE 用户说话, the System SHALL 用识别回调的 RMS 驱动声纹条，并用部分结果更新 overlay 文案
3. WHEN 识别完成, the System SHALL 在 overlay 展示最终识别文本，禁止拉起主界面
4. IF 未授予麦克风权限, the System SHALL 显示「未授予麦克风权限」，禁止假装正在聆听
5. IF 语音识别不可用或识别失败, the System SHALL 显示中文错误，禁止伪造识别结果
