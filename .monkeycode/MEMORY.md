# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[仓库协作与交付流程]
- Date: 2026-11-02
- Context: 用户导入 JARVIS 压缩包并给出后续工作前提；后续补充 APK 压缩与直链要求
- Instructions:
  - 仅使用 origin/main，不使用其他分支
  - 每完成一个可验收步骤后立即提交并推送到原仓库 `https://github.com/ptr193/JARVIS`
  - 后期补充一律写入计划列表再执行，禁止当作临时热修复
  - 每一步完成后必须跑对应测试，测试通过才算该步完成
  - Debug APK 仅在设计文档闭环后打包，由用户实机审查
  - 未完成实机测试前禁止打包 Release
  - GitHub token 只用于 push，不得写入仓库、文档或聊天回复
  - APK 体积大于 100MB 时先打成 `.7z` 再上传，上传后提供压缩包下载直链
  - 压缩包已解压入仓，禁止重复解压

[负荷测试必须逐级累加]
- Date: 2026-11-12
- Context: 用户纠正负荷测试做法：先前散测不计次数
- Instructions:
  - 负荷测试共 30 轮，一轮通过后下一轮必须加重
  - 第 1 轮轻量测主功能，第 2 轮轻量测全部功能，之后全功能加压
  - 负荷指给代码加负担，测效率、性能与 bug
  - 一轮失败即修并重跑该轮，禁止把非累加散测计入 30 轮

[Project Knowledge Summary]
- Date: 2026-10-30
- Context: Discovered by Agent while importing JARVIS archive
- Category: Environment Configuration
- Instructions:
  - 包名 `com.jarvis.pineapple`，Kotlin + Jetpack Compose + Koin + SQLDelight + Ktor
  - 远程仓库 `origin` = `https://github.com/ptr193/JARVIS`，当前分支 main
  - 本沙箱初始无 JDK / Android SDK，构建前需安装 JDK 17 与 Android SDK platform-35
  - 压缩包内 `root/jarvis-final` 为旧副本，已丢弃，不以该目录为准
  - 规格权威顺序：产品规格书 → 产品计划书 → 补充计划 → 补充内容规格书 → 人格灵魂描述

[Project Knowledge Summary]
- Date: 2026-10-31
- Context: Discovered by Agent while performing 阶段 1 构建基线
- Category: Build Methods
- Instructions:
  - 构建命令：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2`
  - Gradle wrapper 使用 8.11.1；`gradle.properties` 中 `org.gradle.jvmargs=-Xmx1536m`、`kotlin.daemon.jvmargs=-Xmx1g`，4G 堆会触发 cgroup OOM
  - Debug 签名需要 `/root/.android/debug.keystore`（storepass/keypass=android，alias=androiddebugkey）
  - `assembleDebug` 用于阶段验收；Debug APK 交付给用户实机审查仍按阶段 12；未实机通过前禁止 `assembleRelease`
