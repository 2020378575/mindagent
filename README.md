# EvidenceLab

EvidenceLab 是面向科研证据工作区的多智能体系统：围绕项目级资料做检索增强问答、方案比较、失败诊断与实验复盘，并通过可恢复异步任务与三层研究记忆沉淀可审计结论。

- 统一 AgentRuntime：上下文装配、意图路由、证据检索、证据审查与决策生成，有限步循环（最多 8 步）与持久化检查点。
- 可恢复 ResearchTask：论文解析与科研决策从单次 SSE 请求解耦，支持进度查询、失败重试与断线恢复。
- 三层研究记忆：工作记忆、项目短期记忆与长期研究记忆；仅已确认或实验验证的结论可进入长期记忆。
- 项目级 RAG：PDF / Markdown / 实验日志结构化切块，Chroma + BM25 融合检索，按 `projectId` 隔离。

微调与本地模型接入见：[docs/qwen25-7b-lora-finetune-guide.md](docs/qwen25-7b-lora-finetune-guide.md)。

## 目录

```text
src/main/java/com/mindbridge/agent
├── config
├── controller
├── domain
├── dto
├── repository
├── security
└── service
    ├── ai
    ├── agent          # 研究 Agent 环
    ├── document       # 位置感知解析
    ├── knowledge      # 项目级 RAG
    ├── memory         # 三层研究记忆
    ├── mcp
    ├── project
    └── task           # 可恢复任务运行时
```

## Agent 环

```text
ResearchContextAgent
-> SupervisorAgent
-> EvidenceAgent
-> EvidenceCriticAgent
-> DecisionAgent / ResearchAssistantAgent
```

## 快速开始

```bash
cd /path/to/EvidenceLab
mvn -Dmaven.repo.local=.m2/repository clean test
bash scripts/run-evidencelab-eval.sh \
  --dataset src/main/resources/rag-eval/evidencelab-rag-eval-v1.json \
  --output docs/evaluation/evidencelab-v1-results.md
```

默认演示账号（首次启动写入）：

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| admin | admin123 | Research Admin |
| student | student123 | 普通用户 |

## 评测

版本化评测集：`src/main/resources/rag-eval/evidencelab-rag-eval-v1.json`

验收门槛：

- Intent accuracy ≥ 0.90
- Recall@5 ≥ 0.85
- Claim-source support rate ≥ 0.95
- Structured output success rate ≥ 0.98
- Cross-project leakage count = 0
- Task recovery scenarios passed = 4

内置知识库位于 `src/main/resources/knowledge/`，覆盖 LoRA/QLoRA、OOM 日志、冲突论文结论、基线 README 与跨项目隔离样本。

## 配置要点

见 `src/main/resources/application.yml`。评测数据集默认：

```yaml
mindbridge:
  rag-eval:
    dataset: classpath:rag-eval/evidencelab-rag-eval-v1.json
```

Java 包名在本阶段仍为 `com.mindbridge.agent`；Maven 坐标已切换为 EvidenceLab。
