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
- Date: 2026-10-30
- Context: 用户导入 JARVIS 压缩包并给出后续工作前提
- Instructions:
  - 仅使用 origin/main，不使用其他分支
  - 每完成一个可验收步骤后立即提交并推送到原仓库 `https://github.com/ptr193/JARVIS`
  - 后期补充一律写入计划列表再执行，禁止当作临时热修复
  - 每一步完成后必须跑对应测试，测试通过才算该步完成
  - Debug APK 仅在设计文档闭环后打包，由用户实机审查
  - 未完成实机测试前禁止打包 Release
  - GitHub token 只用于 push，不得写入仓库、文档或聊天回复

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
