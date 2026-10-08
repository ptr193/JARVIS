# JARVIS 模型适配报告

> 对应《JARVIS_补充计划.md》第 3 节。
> 生成日期：2026-10-09

---

## 1. 适配模型清单

| # | 模型 | 规格 | 量化 | 本地文件名 | 大小 | 来源仓库 |
|---|------|------|------|-----------|------|----------|
| 1 | Gemma 4 E2B | 2.5B (effective 2B) | int8/mixed | `gemma4_e2b_2_5b.litertlm` | ~2.5 GB | `litert-community/gemma-4-E2B-it-litert-lm` |
| 2 | Gemma 4 12B | 12B | int8 | `gemma4_12b.litertlm` | ~6.7 GB | `litert-community/gemma-4-12B-it-litert-lm` |
| 3 | LFM 2.5 | 1.2B Instruct (替代原计划 1.7B) | int8 | `lfm2_5_1_7b.litertlm` | ~1.2 GB | `litert-community/LFM2.5-1.2B-Instruct` |
| 4 | Qwen 3 | 0.6B | 默认 | `qwen3_0_6b.litertlm` | ~586 MB | `litert-community/Qwen3-0.6B` |

> **关于 LFM2.5 1.7B**：Liquid AI 官方 LFM2.5 系列并无 1.7B 规格，实际可选规格为 230M / 1.2B / 2.6B。本项目采用最接近 1.7B 的 **LFM2.5-1.2B-Instruct (int8)** 作为替代，配置文件 `lfm2.5_1.7b.yaml` 中已注明。

---

## 2. 文件校验（SHA256）

| 模型文件 | SHA256 |
|----------|--------|
| `qwen3_0_6b.litertlm` | `555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4` |
| `lfm2_5_1_7b.litertlm` | `41c192feac3a028cfd35f4a0c5db2a70961dc388ecb4ad2cac9a094be0a078ea` |
| `gemma4_e2b_2_5b.litertlm` | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| `gemma4_12b.litertlm` | （下载完成后补充） |

---

## 3. 格式说明

LiteRT-LM 官方模型格式为 **`.litertlm`**（非传统 `.tflite`）。本项目代码 `LocalModels.kt` 与 YAML 配置均已统一使用 `.litertlm`。

应用运行时模型文件需放置于：`context.filesDir/models/`

---

## 4. 加载与推理冒烟测试（待实机执行）

> 以下测试需在 Android 真机上完成（沙箱无设备）。测试方法：在应用内切换到对应模型，发送一句 "你好"，观察是否正常返回。

| 模型 | 加载测试 | 推理冒烟 | 输出格式一致性 | 状态 |
|------|----------|----------|----------------|------|
| Gemma 4 E2B 2.5B | ⏳ 待实机 | ⏳ 待实机 | ⏳ 待实机 | 未执行 |
| Gemma 4 12B | ⏳ 待实机 | ⏳ 待实机 | ⏳ 待实机 | 未执行 |
| LFM 2.5 1.2B | ⏳ 待实机 | ⏳ 待实机 | ⏳ 待实机 | 未执行 |
| Qwen 3 0.6B | ⏳ 待实机 | ⏳ 待实机 | ⏳ 待实机 | 未执行 |

---

## 5. 性能指标（待实机采集）

> 以下指标需在真机上通过 LiteRT-LM profiler 或日志采集。

| 模型 | 启动耗时 | 首 token 延迟 (TTFT) | 吞吐 (tok/s) | 内存占用 | 状态 |
|------|----------|----------------------|--------------|----------|------|
| Gemma 4 E2B 2.5B | — | — | — | — | 待实机 |
| Gemma 4 12B | — | — | — | — | 待实机 |
| LFM 2.5 1.2B | — | — | — | — | 待实机 |
| Qwen 3 0.6B | — | — | — | — | 待实机 |

---

## 6. 已知限制

1. **Gemma 4 12B**：对设备内存要求极高（建议 ≥16 GB RAM），仅在高端设备上可用，推理速度较慢。
2. **Gemma 4 E2B 2.5B**：在中低端设备上推理可能缓慢。
3. **LFM 2.5 1.2B**：长文本生成能力有限。
4. **Qwen 3 0.6B**：超小模型，能力有限，适合简单指令和快速响应。
5. 所有模型均需用户手动将 `.litertlm` 文件放入应用私有目录。

---

*文档版本：v1.0 | 生成日期：2026-10-09*
