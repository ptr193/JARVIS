# 需求实施计划

- [x] 0. 仓库导入与计划文档
  - 解压工程、丢弃 `root/` 旧副本、删除 zip、补 `.gitignore`
  - 写出 `docs/实施计划.md` 与本规格目录
  - 推送到 origin/main
  - 验收：工作区无 zip；main 含源码与计划

- [x] 1. 构建基线可编译
  - 安装 JDK 17 与 Android SDK 35
  - 修正 AGP/Gradle/Kotlin 版本冲突（若有）
  - 运行 `./gradlew testDebugUnitTest` 与 `assembleDebug`
  - 将真实结果写入 `docs/test_report_stage1.md`
  - 验收：两条命令退出码 0，或文档记录不可达卡点

- [x] 1.1 人格、对话、模型调度
  - 接通 Chat / Personality / ModelScheduler 与导航中的模型选择、语音聊天
  - 无模型时返回明确错误
  - [x] 1.1.1 运行 PersonalityTest、ChatMessageTest、ConversationPipelineIntegrationTest

- [x] 1.2 唤醒、保活、默认助手
  - WakeEngine 真实监听或权限提示；KeepAliveService 前台通知
  - [x] 1.2.1 运行 KeepAliveServiceOrderingTest、WakeWordPipelineIntegrationTest

- [x] 2. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 2.1 虚拟机 PRoot 真实路径
  - ABI 匹配二进制、真实下载校验、终端走 ProotManager
  - 缺失组件时中文错误，禁止假 shell
  - [x] 2.1.1 运行 VmManagerTest、VmSubsystemIntegrationTest

- [x] 2.2 系统控制与权限门面
  - 电话短信日程提醒导航媒体设置跨应用；无权限不假成功
  - 提醒到期发通知
  - [x] 2.2.1 运行 PermissionControllerTest

- [x] 3. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 3.1 智能家居真实传输或明确未检测到
  - DeviceTransport 真实请求；扫描空列表可测试
  - [x] 3.1.1 运行 SmartHomeAdapterTest、SmartHubSubsystemIntegrationTest

- [x] 3.2 感知层去掉硬编码成功
  - 传感器/ML Kit 真实调用；无相机权限失败可测
  - [x] 3.2.1 运行 ObjectDetectorTest

- [x] 3.3 健康模块去掉假生命体征
  - 无硬件时测量失败；用药提醒注册 AlarmManager
  - [x] 3.3.1 运行 HealthModulesTest

- [x] 4. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 4.1 安全中心与设备管理员
  - Manifest + device_admin；未激活拒绝 lock/wipe
  - 加密走 CryptoManager

- [x] 4.2 自主决策、记忆、调度、插件
  - 启动注册能力画像；记忆导入导出；应急动作走系统 API 或报缺权限
  - [x] 4.2.1 运行 AutonomousModulesTest

- [x] 4.3 设置十标签绑定真实 Manager
  - 禁止假日志/假声线/空 onClick

- [x] 5. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 5.1 全量回归并打包 Debug APK
  - testDebugUnitTest、lintDebug 记录、assembleDebug
  - 更新 bug_report / regression_test_report / DOWNLOAD.md
  - 验收：测试全绿；Debug APK 可供用户实机

- [ ] 5.2 实机反馈纳入计划后再修复
  - 用户清单未勾选前不执行 assembleRelease
  - 对应 Requirement 1.3、Requirement 2.3

- [x] 6. 恢复本会话构建环境
  - 安装 JDK 17，设置 JAVA_HOME
  - 安装 Android SDK platform-35 / build-tools-35.0.0，写 `local.properties`（不入 Git）
  - 确认 debug.keystore 可用
  - 对应 Requirement 2.1

- [x] 6.1 验证现有测试与 Debug 构建仍可通过
  - 运行 `./gradlew testDebugUnitTest`
  - 运行 `./gradlew assembleDebug`
  - 将结果写入 `docs/test_report_stage1.md` 更新段
  - 对应 Requirement 2.1、Requirement 2.2

- [x] 6.2 修复 ModelScheduler Koin 启动闪退（BUG-2026-1103）
  - AppModule 中 `ModelScheduler(userModel = ...)` 必须 `get<UserLocalModel>()`，禁止无类型 `get()` 解析 `AiModel` 接口
  - 在 StartupHardeningTest 增加静态断言锁死该绑定
  - 运行 `./gradlew testDebugUnitTest`
  - 对应 Requirement 1.3

- [x] 7. 补齐相机实时采集入口
  - 为追踪矩阵 PARTIAL 项 #33/#34/#35/#94/#112/#113 增加可触达相机预览/拍照入口
  - 无相机权限时返回中文失败，禁止假成功
  - 对应 Requirement 8.1、Requirement 9.1、设计 P3/P5

- [x] 7.1 实现相机预览与拍照 Compose 组件
  - 在设置/智能中枢可打开预览，拍照后把 Bitmap/文件交给感知与安全 Manager
  - 对应 Requirement 8.1

- [x] 7.2 接通人脸识别与身份比对采集路径
  - SmartHub/SecurityTab 调用 ML Kit 真实检测；无权限失败可测
  - 对应 Requirement 8.1、功能 #34/#35

- [x] 7.3 接通物体检测、文档扫描与视觉翻译采集路径
  - 物体检测 #94、文档扫描 #112、视觉翻译 #113 走同一采集入口
  - 对应 Requirement 8.1、功能 #94/#112/#113

- [x] 7.4 为相机采集路径编写单元测试
  - 覆盖无权限失败、有图时调用真实 Detector 接口
  - 对应 Requirement 2.2、设计 P3

- [x] 8. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 9. 对照规格做失败关闭复核
  - 扫描剩余硬编码成功/`return true` 冒充完成路径
  - 缺硬件/云 URL 时保持中文错误，RESERVED 不假成功
  - 对应 Requirement 9、设计 P1–P5

- [x] 9.1 修复本轮复核发现的失败关闭缺口
  - 只改计划第 8 节已登记项，禁止顺手热修复无关模块
  - 对应 Requirement 1.2、Requirement 9

- [x] 9.2 更新需求追踪矩阵 PARTIAL 证据
  - 相机入口接通后把对应行从「无采集入口」改为可触达证据
  - 对应 Requirement 9.3

- [x] 10. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [ ] 11. 全量回归、打包 Debug APK 并上传 7z
  - `testDebugUnitTest` 全绿后 `assembleDebug`
  - APK 复制到 `apk/jarvis-debug.apk`，压缩为 `apk/jarvis-debug.apk.7z`
  - 更新 `docs/DOWNLOAD.md` SHA256/大小/Git SHA，推送 origin/main
  - 回复压缩包下载直链
  - 对应 Requirement 1.1、Requirement 2.1、Requirement 2.2

- [ ] 11.1 更新回归与 bug 文档
  - 写入 `docs/regression_test_report.md`、`docs/bug_report.md`、`docs/实施计划.md` 第 8 节状态
  - 对应 Requirement 1.2
