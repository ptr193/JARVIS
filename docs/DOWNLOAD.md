# JARVIS 交付物与直连下载路径

> 生成日期：2026-11-23 | 版本：v0.0.1（Debug，40 轮加压 + 440 项 JVM 回归）

---

## 1. 交付物清单

| 交付物 | 路径 | 状态 |
|--------|------|------|
| 完整源码 | 本仓库根目录 | ✅ |
| Debug APK | `apk/jarvis-debug.apk` | ✅ 可用 |
| Release APK | — | ⛔ **未交付**（未实机测试，按计划 §5.2 阻断） |
| 需求追踪矩阵 | `docs/requirements_traceability.md` | ✅ |
| 仓库结构说明 | `docs/repo_structure.md` | ✅ |
| 模型配置 | `configs/models/*.yaml` | ✅ |
| 模型适配报告 | `docs/model_adaptation_report.md` | ✅ |
| 阶段一测试报告 | `docs/test_report_stage1.md` | ✅ |
| Bug 报告 | `docs/bug_report.md` | ✅ |
| 回归测试报告 | `docs/regression_test_report.md` | ✅ |
| 流程合规记录 | `docs/process_compliance.md` | ✅ |
| Release 前置清单 | `docs/release_checklist.md` | ✅ |

---

## 2. Debug APK（可直接下载）

APK 体积大于 100MB，仓库提供 `.7z` 压缩包。请下载后解压再安装。

```text
版本：   v0.0.1-debug（40 轮加压 + 440 项 JVM 回归）
压缩包： apk/jarvis-debug.apk.7z
压缩包大小： 80,451,252 字节（76.7 MiB）
压缩包 SHA256： efeb70b8ac1ff81a0474354dfc2769c2756d2472f15f08478fdff4373fd36523
APK 大小： 219,978,337 字节（209.8 MiB）
APK SHA256： 3462ecdcbc2d064c6c1158eb2a39e0df5ed52d6debebc20831e80ec30c766993
构建：   2026-11-23
构建命令：JAVA_HOME=/root/.local/share/mise/installs/java/17.0.20+101 ANDROID_HOME=/workspace/.android-sdk ANDROID_SDK_ROOT=/workspace/.android-sdk ./gradlew --no-daemon :app:assembleDebug
代码基线：ce7dd48；交付物以 origin/main 最新提交为准
直链：   https://github.com/ptr193/JARVIS/raw/main/apk/jarvis-debug.apk.7z
```

**下载与校验：**

```bash
# 下载压缩包
# https://github.com/ptr193/JARVIS/raw/main/apk/jarvis-debug.apk.7z

sha256sum apk/jarvis-debug.apk.7z
```

期望：

```text
efeb70b8ac1ff81a0474354dfc2769c2756d2472f15f08478fdff4373fd36523
```

解压后校验 APK：

```text
3462ecdcbc2d064c6c1158eb2a39e0df5ed52d6debebc20831e80ec30c766993
```

本包基于最终修复后的代码构建：40/40 轮递增加压、440/440 项 Debug JVM 测试通过，Lint 为 0 error / 43 warning。本机指令（系统控制、翻译、root、虚拟机）统一要求以 `/` 或 `／` 开头显式触发，普通聊天不再被硬编码关键词劫持；无模型时普通聊天返回明确的无模型提示。压缩包通过 `7z t`，包内 APK 流式解压哈希与上述 APK SHA256 一致。`*.apk` 不入 Git，仅提交 `.7z`。真机硬件、电话、短信、传感器、模型推理、云服务与 arm64 PRoot 尚需按回归报告第 6 节验收；Release 仍阻断。

---

## 3. Release APK（未交付）

**不提供 Release 下载路径。**

原因：`JARVIS_补充计划.md` §5.2 / §6.2 规定，未完成实机测试不得打包发行版。
当前环境无法执行实机测试（无真机/模拟器，详见 `docs/process_compliance.md` 第 3 节），
因此 Release 打包被阻断。

历史目录中的 `jarvis-release.apk`（2026-10-18）为**此前流程违规产物**，
未经任何实机验证，**不作为有效交付物**，请勿使用。

---

## 4. 模型适配包 / 配置

| 模型 | 配置文件 | 权重 |
|------|---------|------|
| Gemma 4 E2B 2.5B | `configs/models/gemma4_e2b_2.5b.yaml` | `configs/models/files/gemma4_e2b_2_5b.litertlm.part.00/01` |
| Gemma 4 12B | `configs/models/gemma4_12b.yaml` | `configs/models/files/gemma4_12b.litertlm.part.00-03` |
| LFM 2.5 1.2B Instruct | `configs/models/lfm2.5_1.7b.yaml`（历史文件名） | `configs/models/files/lfm2_5_1_7b.litertlm` |
| Qwen 3 0.6B | `configs/models/qwen3_0.6b.yaml` | `configs/models/files/qwen3_0_6b.litertlm` |

> 上述路径是配置/分片标识，不证明权重已完整入库或可推理；仓库中仍存在 Git LFS 指针。默认模型应在应用内下载并通过官方大小与 SHA-256 校验。真机下载、加载和冒烟推理均为 NOT_RUN。

---

## 5. 源码包

- 源码位于本目录，可直接使用。
- 远程仓库 `https://github.com/ptr193/JARVIS`，分支 `main`。
- 构建产物 `app/build/` 为中间产物，可删除后重新构建。

---

## 6. 使用说明

1. 将 `apk/jarvis-debug.apk` 安装到 Android 7.0+ 设备。
2. 首次启动授予所需权限（电话/短信/定位/麦克风/通知/无障碍）。
3. 在 **设置 → 服务** 触发模型下载，或使用 **设置 → 工具** 导入本地模型。
4. 也可在 **设置 → 服务** 配置外接 OpenAI 兼容 API（Key/基础地址/模型名）或个人服务器地址，直接使用远程模型；配置会持久化并在下次启动时恢复。图片附件会以真实 base64 发送给支持视觉的 API 模型，不支持的模型会明确提示。
5. 长按对话消息可复制、引用、删除或重新生成回复。
6. 本机指令需以 `/` 或 `／` 开头（如 `/音量 50`、`/打开微信`、`/翻译成英文 你好`、`/终端执行 uname -a`）；以 `/` 开头的未识别指令会返回可用示例，不以普通聊天处理。
7. 按 `docs/release_checklist.md` 第 3～4 节执行实机与外部验收清单并反馈。

---

*文档版本：v4.1 | 生成日期：2026-11-23*
