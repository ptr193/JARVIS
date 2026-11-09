# 需求实施计划

- [x] 1. Overlay 听写（A1）
  - `JarvisVoiceInteractionSession` 在 onShow 启动系统 SpeechRecognizer
  - RMS 驱动声纹；部分/最终结果更新 hint
  - 无权限或 ASR 不可用时中文错误，不拉主界面
  - 对应 Requirement 1.1–1.5

  - [x] 1.1 编写 overlay 听写静态测试
    - 对应 Requirement 1.3、1.4

- [x] 2. 检查点 - 确保所有测试通过
  - `testDebugUnitTest` 全绿后 `assembleDebug`，打 7z，更新 DOWNLOAD.md，推 origin/main
