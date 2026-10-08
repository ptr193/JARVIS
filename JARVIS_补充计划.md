# JARVIS_补充计划

## 0. 任务总目标

严格按照 `JARVIS_产品规格书.md` 完成 JARVIS 项目的设计、实现、仓库整理、模型适配、调试、实机测试与发行版打包，并输出可直连下载的产物路径。**禁止跳步，禁止在未完成实机测试与 bug 检查前打包发行版。**

---

## 1. 规格书完整实现（第一优先级）

### 1.1 前置动作

1. 完整读取 `JARVIS_产品规格书.md`，逐条提取：
   - 功能需求（FR）
   - 非功能需求（NFR）
   - 架构设计
   - 接口定义
   - 数据流 / 状态机
   - 模型要求
   - 打包与发布要求
   - 测试与验收标准
2. 生成 `docs/requirements_traceability.md`，建立「规格条目 → 代码文件 → 测试用例 → 验收状态」的追踪矩阵。

### 1.2 实现要求

1. 按规格书实现全部模块，不得遗漏、不得用占位符冒充完成。
2. 每个模块必须有：
   - 源码
   - 单元测试
   - 集成测试（如规格书要求）
3. 实现完成后，逐条对照追踪矩阵，标记 `DONE` / `PARTIAL` / `MISSING`。
4. 若有任何 `PARTIAL` 或 `MISSING`，必须继续实现，不得进入下一阶段。

### 1.3 交付物

- 完整源码
- `docs/requirements_traceability.md`
- 测试报告 `docs/test_report_stage1.md`

---

## 2. 上传仓库并整理

### 2.1 上传

1. 使用提供的 token 将项目推送到指定仓库。
2. 确认：
   - 分支：`main`（或规格书指定分支）
   - 提交信息清晰，按模块拆分 commit
   - 不包含密钥、token、临时文件、大体积二进制（除非规格书要求）

### 2.2 仓库整理

1. 目录结构规范化，例如：

   ```text
   JARVIS/
   ├── src/
   ├── models/
   ├── tests/
   ├── docs/
   ├── scripts/
   ├── configs/
   ├── README.md
   ├── .gitignore
   └── LICENSE
   ```

2. 补齐：
   - `README.md`：项目说明、构建、运行、模型适配、下载路径
   - `.gitignore`
   - `requirements.txt` / `pyproject.toml` / `package.json`（按技术栈）
   - `CHANGELOG.md`
3. 清理无用文件、重复文件、临时产物。
4. 确认仓库可克隆、可构建、可运行。

### 2.3 交付物

- 仓库链接
- 仓库结构说明 `docs/repo_structure.md`

---

## 3. 模型适配

### 3.1 适配目标模型

必须适配以下四个模型：

1. Gemma 4 E2B 2.5B
2. Gemma 4 12B
3. LFM2.5 1.7B
4. Qwen3 0.6B

### 3.2 适配要求

1. 为每个模型提供独立配置：
   - 模型路径 / 下载源
   - 推理后端（如 llama.cpp / transformers / ONNX / vLLM 等，按规格书）
   - 量化方式（如适用）
   - 上下文长度、线程数、显存/内存要求
2. 在 `configs/models/` 下生成：
   - `gemma4_e2b_2.5b.yaml`
   - `gemma4_12b.yaml`
   - `lfm2.5_1.7b.yaml`
   - `qwen3_0.6b.yaml`
3. 代码中模型加载必须可切换，禁止硬编码单一模型。
4. 对每个模型执行：
   - 加载测试
   - 推理冒烟测试
   - 输出格式一致性测试
5. 记录每个模型的：
   - 启动耗时
   - 首 token 延迟
   - 吞吐
   - 内存/显存占用
   - 已知限制

### 3.3 交付物

- `configs/models/*.yaml`
- `docs/model_adaptation_report.md`
- 每个模型的冒烟测试日志

---

## 4. 直连下载路径

### 4.1 要求

所有工作完成后，提供可直接下载的路径，供实机测试使用。

### 4.2 需提供

1. 源码包下载路径
2. 各模型适配包 / 配置下载路径
3. 调试版（debug）下载路径
4. 发行版（release）下载路径（仅在完成实机测试与 bug 检查后提供）
5. 校验信息：
   - SHA256
   - 文件大小
   - 版本号

### 4.3 格式示例

```text
版本：vX.Y.Z
源码：https://.../jarvis-src-vX.Y.Z.zip
Debug：https://.../jarvis-debug-vX.Y.Z.zip
Release：https://.../jarvis-release-vX.Y.Z.zip
SHA256：...
```

---

## 5. 流程合规性质询（必须正面回答）

计划文档中有明确说明：debug 打包后就进行实机测试，**为什么跳过实机测试直接打包发行版？**

### 5.1 要求 AI 必须做到

1. 正面回答该问题，不得回避。
2. 若此前确实跳过，必须：
   - 说明跳过原因
   - 承认流程违规
   - 立即回退到正确流程
3. 正确流程固定为：

   ```text
   实现 → 单元测试 → 集成测试 → Debug 打包 → 实机测试 → Bug 修复 → 回归测试 → Release 打包
   ```

4. 在 `docs/process_compliance.md` 中记录：
   - 实际执行流程
   - 与计划流程的差异
   - 差异原因
   - 纠正措施

### 5.2 禁止行为

- 禁止在未完成实机测试前打包发行版
- 禁止用"预计没问题"替代实机验证
- 禁止隐瞒流程跳步

---

## 6. 异常与 Bug 检查（发行版前置门槛）

### 6.1 检查范围

1. 静态检查：
   - lint
   - type check
   - 依赖漏洞扫描
2. 动态检查：
   - 单元测试
   - 集成测试
   - 端到端测试
   - 长时间运行稳定性测试
   - 内存泄漏检查
3. 模型相关：
   - 四个模型逐一加载与推理
   - 异常输入、超长输入、空输入
   - 并发/队列场景（如适用）
4. 打包相关：
   - Debug 包在目标环境可运行
   - Release 包在目标环境可运行
   - 安装/卸载/升级流程正常

### 6.2 门槛条件

只有同时满足以下条件，才允许打包发行版：

- [ ] 规格书追踪矩阵全部 `DONE`
- [ ] 所有单元测试通过
- [ ] 所有集成测试通过
- [ ] Debug 包已完成实机测试
- [ ] 实机测试中发现的问题已全部修复并回归
- [ ] 四个模型均通过冒烟测试
- [ ] 无已知 P0/P1 bug
- [ ] `docs/process_compliance.md` 已填写并合规

### 6.3 交付物

- `docs/bug_report.md`
- `docs/regression_test_report.md`
- `docs/release_checklist.md`

---

## 7. 执行顺序（强制）

```text
1. 读取并拆解规格书
2. 实现全部功能
3. 单元测试 + 集成测试
4. 上传仓库并整理
5. 适配四个模型
6. Debug 打包
7. 实机测试（必须）
8. 修复 bug + 回归测试
9. 检查无异常
10. Release 打包
11. 提供直连下载路径
12. 回答流程合规性质询
```

---

## 8. 最终交付清单

- [ ] 完整源码
- [ ] `docs/requirements_traceability.md`
- [ ] `docs/repo_structure.md`
- [ ] `configs/models/*.yaml`
- [ ] `docs/model_adaptation_report.md`
- [ ] `docs/test_report_stage1.md`
- [ ] `docs/bug_report.md`
- [ ] `docs/regression_test_report.md`
- [ ] `docs/process_compliance.md`
- [ ] `docs/release_checklist.md`
- [ ] Debug 包
- [ ] Release 包
- [ ] 直连下载路径 + SHA256
- [ ] 流程合规性质询的正面回答

---

## 9. 给 AI 的硬性约束

1. 不得跳步。
2. 不得伪造测试结果。
3. 不得在未实机测试前发布 Release。
4. 不得隐瞒 bug。
5. 每个阶段必须有可核查产物。
6. 若无法完成，必须明确说明卡点，而不是假装完成。
