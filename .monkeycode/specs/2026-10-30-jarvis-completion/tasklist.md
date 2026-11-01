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
  - [x]* 1.1.1 运行 PersonalityTest、ChatMessageTest、ConversationPipelineIntegrationTest

- [x] 1.2 唤醒、保活、默认助手
  - WakeEngine 真实监听或权限提示；KeepAliveService 前台通知
  - [x]* 1.2.1 运行 KeepAliveServiceOrderingTest、WakeWordPipelineIntegrationTest

- [x] 2. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 2.1 虚拟机 PRoot 真实路径
  - ABI 匹配二进制、真实下载校验、终端走 ProotManager
  - 缺失组件时中文错误，禁止假 shell
  - [x]* 2.1.1 运行 VmManagerTest、VmSubsystemIntegrationTest

- [x] 2.2 系统控制与权限门面
  - 电话短信日程提醒导航媒体设置跨应用；无权限不假成功
  - 提醒到期发通知
  - [x]* 2.2.1 运行 PermissionControllerTest

- [x] 3. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 3.1 智能家居真实传输或明确未检测到
  - DeviceTransport 真实请求；扫描空列表可测试
  - [x]* 3.1.1 运行 SmartHomeAdapterTest、SmartHubSubsystemIntegrationTest

- [x] 3.2 感知层去掉硬编码成功
  - 传感器/ML Kit 真实调用；无相机权限失败可测
  - [x]* 3.2.1 运行 ObjectDetectorTest

- [x] 3.3 健康模块去掉假生命体征
  - 无硬件时测量失败；用药提醒注册 AlarmManager
  - [x]* 3.3.1 运行 HealthModulesTest

- [x] 4. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 4.1 安全中心与设备管理员
  - Manifest + device_admin；未激活拒绝 lock/wipe
  - 加密走 CryptoManager

- [x] 4.2 自主决策、记忆、调度、插件
  - 启动注册能力画像；记忆导入导出；应急动作走系统 API 或报缺权限
  - [x]* 4.2.1 运行 AutonomousModulesTest

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
