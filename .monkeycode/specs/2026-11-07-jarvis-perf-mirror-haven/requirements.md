# Requirements Document

## Introduction

本轮按用户实机 4 条反馈，收敛设置页卡顿、模型/发行版下载超时后的国内镜像切换、Haven 式 PRoot 镜像清单（含 Kali / Rocky / Windows 导入），以及危险权限先说明再弹出系统对话框。科幻预留、Wayland 桌面、关机唤醒不纳入。

## Glossary

- **System**：JARVIS Android 应用（`com.jarvis.pineapple`）
- **国内镜像**：HuggingFace 走 hf-mirror，GitHub 走 ghproxy，Alpine/Kali 走清华/中科大镜像
- **按需授权**：危险权限仅在用户点击对应功能后先说明用途，再弹出系统对话框
- **Haven 式镜像**：PRoot 用户态 rootfs，命令来自真实进程，禁止假 shell

## Requirements

### Requirement 1 设置页卡顿收敛

**User Story:** AS 用户, I want 设置页滑动与状态刷新不卡顿, so that 我能流畅切换标签

#### Acceptance Criteria

1. WHEN 用户滑动或点击设置标签, the System SHALL 立即切换到目标页，避免相邻页同时执行设备采样
2. WHILE 状态标签不可见, the System SHALL 停止 CPU/电池周期采样
3. WHEN 状态标签可见, the System SHALL 在后台线程读取设备状态，再更新界面
4. WHEN 服务标签展示模型列表, the System SHALL 复用已注入的下载管理器，避免重复创建

### Requirement 2 下载超时后国内镜像自动切换

**User Story:** AS 用户, I want 主源超时后自动改走国内镜像, so that 模型和发行版能下完

#### Acceptance Criteria

1. WHEN HuggingFace 主源连接超时或失败, the System SHALL 改用 hf-mirror 继续下载，并提示「主源超时，正在切换国内镜像」
2. WHEN GitHub 主源连接超时或失败, the System SHALL 改用 ghproxy 继续下载
3. WHEN 发行版主地址超时, the System SHALL 依次尝试该条目的 mirrorUrls 与按主机推导的国内镜像
4. IF 全部镜像均失败, the System SHALL 显示中文错误并保留重试，禁止显示假成功
5. WHEN 用户确认首次模型下载, the System SHALL 等待该次下载完成或全部镜像失败，禁止用 30 秒整包超时中断 GB 级文件

### Requirement 3 Haven 式虚拟机镜像

**User Story:** AS 用户, I want 按 Haven/PRoot 清单选择或导入镜像, so that 终端命令来自真实 rootfs

#### Acceptance Criteria

1. WHEN 用户打开可下载发行版, the System SHALL 列出 Kali Linux（含 SHA-256）、Debian、Alpine、Ubuntu、Arch、Void，并标注体积
2. WHEN 用户选择 Rocky Linux 或 Windows, the System SHALL 提示通过导入镜像安装，禁止假装已下载
3. WHEN 发行版声明了 SHA-256, the System SHALL 校验通过后再解压
4. IF 发行版未声明 SHA-256, the System SHALL 尝试下载同地址的 `.sha256` 侧车文件；侧车缺失则返回中文错误并允许手动导入
5. IF 下载或解压失败, the System SHALL 返回中文错误并允许重试或导入

### Requirement 4 权限说明后再系统弹窗

**User Story:** AS 用户, I want 点击相机或麦克风时先看到用途说明, so that 我知道为何授权

#### Acceptance Criteria

1. WHEN 用户首次点击相机拍照, the System SHALL 先展示相机用途说明，确认后再弹出系统权限对话框
2. WHEN 用户首次点击聊天页麦克风, the System SHALL 先展示麦克风用途说明，确认后再进入语音对话
3. WHEN 用户拒绝授权, the System SHALL 显示「未授予权限」
4. WHEN 应用冷启动, the System SHALL 不批量弹出危险权限请求

### Requirement 5 本轮范围边界

**User Story:** AS 维护者, I want 本轮只修这 4 条, so that 范围可控

#### Acceptance Criteria

1. THE System SHALL 把科幻 RESERVED、GPU 占用、Wayland 桌面、关机唤醒留待后续计划
2. THE System SHALL 在未实机通过前禁止打包 Release
