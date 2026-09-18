# EvidenceLab：Qwen2.5-7B LoRA 微调与 Ollama 接入

本文记录在 EvidenceLab 中为**科研助手场景**准备本地模型的流程：LoRA / QLoRA 微调 → adapter 合并 → GGUF 转换 → Ollama 创建。目标是得到可被 `AI_PROVIDER=ollama` 调用的本地模型。

> 历史文档曾面向校园助手数据集（`psychqa_synthetic.jsonl`）。当前产品语义为科研证据工作区；请改用科研对话 / 决策草稿类 JSONL，或继续沿用旧文件名但更换样本内容。

## 产物约定

| 产物 | 建议路径 / 名称 |
| --- | --- |
| 量化 GGUF | `models/evidencelab-qwen2.5-7b-ft/evidencelab-qwen2.5-7b-ft-q4_k_m.gguf` |
| Ollama 模型名 | `evidencelab-qwen2.5-7b-ft:latest` |

## 1. 准备数据

将指令微调样本放到：

```bash
mkdir -p data/lora
# 每行一个 JSON：{"instruction":"...","input":"...","output":"..."} 或项目约定格式
cp /path/to/research-assistant.jsonl data/lora/research-assistant.jsonl
```

样本应覆盖：证据问答、LoRA/QLoRA 取舍、OOM 复盘、引用约束（不编造出处）。

## 2. 服务器训练（示意）

```bash
cd /path/to/EvidenceLab
python3 -m venv /root/evidencelab-lora-env
source /root/evidencelab-lora-env/bin/activate
pip install -U pip
# 按你的训练脚本安装依赖（transformers / peft / bitsandbytes 等）

python train_lora.py \
  --dataset /path/to/EvidenceLab/data/lora/research-assistant.jsonl \
  --output_dir /path/to/EvidenceLab/output/qwen25-7b-evidencelab-lora \
  --system "你是 EvidenceLab 科研证据助手。依据项目资料回答，标明不确定处，不编造引用，不做医疗诊断。"
```

12GB 显存优先 QLoRA（NF4）+ 较小 batch；完整对照见内置知识库 `src/main/resources/knowledge/lora-vs-qlora-12gb.md` 与 `oom-experiment-log.md`。

## 3. 合并 adapter 并导出 GGUF

```bash
# 合并（示意）
python merge_lora.py \
  --adapters .../checkpoint-xxx \
  --output_dir output/evidencelab-qwen2.5-7b-ft-hf

# 转 GGUF + 量化（示意，具体命令以 llama.cpp 版本为准）
python convert_hf_to_gguf.py output/evidencelab-qwen2.5-7b-ft-hf \
  --outfile output/evidencelab-qwen2.5-7b-ft-f16.gguf
./llama-quantize output/evidencelab-qwen2.5-7b-ft-f16.gguf \
  models/evidencelab-qwen2.5-7b-ft/evidencelab-qwen2.5-7b-ft-q4_k_m.gguf Q4_K_M
```

## 4. Ollama Modelfile

`models/evidencelab-qwen2.5-7b-ft/Modelfile` 示例：

```text
FROM ./evidencelab-qwen2.5-7b-ft-q4_k_m.gguf

SYSTEM """
你是 EvidenceLab 科研证据助手，由 Qwen2.5-7B 面向研究工作区适配而来。
依据项目资料回答配置、出处与方案取舍；证据不足时明确说明。
不要编造论文页码或实验数字；不要输出内部工具链、Excel 或 MCP 细节。
"""
```

创建：

```bash
./scripts/create-finetuned-model.sh
# 或：
ollama create evidencelab-qwen2.5-7b-ft:latest -f models/evidencelab-qwen2.5-7b-ft/Modelfile
```

## 5. 接入 EvidenceLab

```yaml
mindbridge:
  ai:
    provider: ollama
    ollama:
      base-url: http://localhost:11434
      model: evidencelab-qwen2.5-7b-ft:latest
```

或：

```bash
AI_PROVIDER=ollama OLLAMA_MODEL=evidencelab-qwen2.5-7b-ft:latest ./scripts/run-dev.sh
```

## 说明

- 配置节点前缀仍为 `mindbridge.*`（与 Java `MindBridgeProperties` 绑定），产品名是 EvidenceLab。
- 若沿用旧目录名 `models/mindbridge-qwen2.5-7b-ft/`，只需把 `OLLAMA_MODEL` 与 Modelfile 中的 system 文案改为科研助手语义即可。
