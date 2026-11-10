# 需求实施计划

- [x] 1. Overlay 听写（A1）
  - `JarvisVoiceInteractionSession` 在 onShow 启动系统 SpeechRecognizer
  - RMS 驱动声纹；部分/最终结果更新 hint
  - 无权限或 ASR 不可用时中文错误，不拉主界面
  - 对应 Requirement 1.1–1.5

  - [x] 1.1 编写 overlay 听写静态测试
    - 对应 Requirement 1.3、1.4

- [x] 2. Overlay 回答（A2）
  - 识别结果进 ChatEngine，TTS 播报，仍不打开主界面
  - 对应 Requirement 2.1–2.3

- [x] 3. Overlay 识屏（A3）
  - `onHandleAssist` 读 AssistStructure，空结构不伪造
  - 对应 Requirement 3.1–3.2

- [x] 4. 主动建议卡片（B1）
  - 保活循环调用 generateContextualSuggestions 并推通知
  - 对应 Requirement 4.1–4.2

- [x] 5. 默认助手引导（B2）
  - 通用页检测 isDefaultAssistant，未设置时给系统入口
  - 对应 Requirement 5.1–5.2

- [x] 6. 多模态入口（C1）
  - overlay 与建议卡片打开 OverlayCameraActivity 识物/扫文档
  - 对应 Requirement 6.1–6.3

- [x] 7. 检查点 - 确保所有测试通过
  - `testDebugUnitTest` 全绿后 `assembleDebug`，打 7z，更新 DOWNLOAD.md，推 origin/main
