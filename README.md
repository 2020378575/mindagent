# EvidenceLab

EvidenceLab 是面向科研证据工作区的多智能体系统：围绕**项目级资料**做检索增强问答、方案比较、失败诊断与实验复盘，并通过可恢复异步任务与三层研究记忆沉淀可审计结论。

| 能力 | 说明 |
| --- | --- |
| 统一 AgentRuntime | 上下文装配 → 意图路由 → 证据检索 → 证据审查 → 决策/问答，最多 8 步，带持久化检查点 |
| 可恢复 ResearchTask | 长流程与 SSE 解耦；支持进度查询、失败重试、断线恢复与幂等提交 |
| 三层研究记忆 | 工作记忆 / 项目短期记忆 / 长期研究记忆；仅已确认或实验验证的结论可晋升 |
| 项目级 RAG | PDF / Markdown / 实验日志结构化切块；Chroma + BM25；按 `projectId` 隔离 |

> 历史仓库曾命名为 MindBridge（校园助手场景）。本阶段产品语义已切换为 EvidenceLab。Java 包名暂为 `com.mindbridge.agent`，配置前缀暂为 `mindbridge.*`（与 `MindBridgeProperties` 绑定）；Maven 坐标与对外文档均使用 EvidenceLab。

## 快速开始

### 环境

| 软件 | 用途 |
| --- | --- |
| JDK 17 | 构建与运行 |
| Maven 3.9+ | 构建 |
| Redis | 短期记忆（本地可先起 Docker） |
| （可选）MySQL 8 / Chroma / Mailpit | 生产形态或完整链路 |

### 本地跑通（OpenAI 兼容 API）

```bash
export OPENAI_API_KEY=sk-...
# 可选：OPENAI_BASE_URL / OPENAI_MODEL
./scripts/run-dev.sh
```

浏览器打开 [http://localhost:8080](http://localhost:8080)。

默认演示账号（首次启动由 `DataInitializer` 写入）：

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| admin | admin123 | Research Admin |
| student | student123 | 普通用户 |

### Docker Compose

```bash
export OPENAI_API_KEY=sk-...
docker compose up --build
```

服务：应用 `8080`、MySQL `3306`、Redis `6379`、Chroma `8000`、Mailpit UI `8025`。

### 测试与验收

```bash
mvn -Dmaven.repo.local=.m2/repository clean test

bash scripts/run-evidencelab-eval.sh \
  --dataset src/main/resources/rag-eval/evidencelab-rag-eval-v1.json \
  --output docs/evaluation/evidencelab-v1-results.md
```

## 目录结构

```text
src/main/java/com/mindbridge/agent
├── config                 # 配置、安全、AI/MCP Bean
├── controller             # 项目 / 任务 / 决策 / 资料 / 助手 API
├── domain                 # JPA 实体与枚举
├── dto
├── repository
├── security
└── service
    ├── ai                 # 模型适配与 Prompt
    ├── agent              # 研究 Agent 环
    ├── document           # 位置感知解析
    ├── knowledge          # 项目级 RAG
    ├── memory             # 三层研究记忆
    ├── mcp                # 运维归档与预警工具
    ├── project
    ├── decision
    └── task               # 可恢复任务运行时
src/main/resources
├── knowledge/             # 内置科研证据夹具
├── rag-eval/              # 版本化评测集
└── static/                # 决策优先工作区前端
```

## Agent 环与意图

```text
ResearchContextAgent
-> SupervisorAgent
-> EvidenceAgent
-> EvidenceCriticAgent
-> DecisionAgent / ResearchAssistantAgent
```

意图（`IntentType`）：

- `GENERAL_CHAT` — 轻量问答，不强制进证据链
- `EVIDENCE_QUERY` — 出处 / 配置 / 数字引用
- `RESEARCH_DECISION` — 方案取舍，产出结构化决策草稿
- `RESULT_REVIEW` — 对照实验日志复核先前结论

## 主要 API（摘录）

| 路径 | 说明 |
| --- | --- |
| `GET /` | 研究工作区前端 |
| `POST /api/projects` | 创建项目 |
| `POST /api/projects/{id}/sources` | 上传资料（multipart） |
| `POST /api/projects/{id}/assistant/stream` | 项目内助手 SSE |
| `POST /api/projects/{id}/tasks` | 创建可恢复研究任务 |
| `GET /api/projects/{id}/tasks/{publicId}/events` | 任务进度 SSE |
| `GET/POST /api/projects/{id}/decisions/...` | 决策确认 / 复核 / 再生 |
| `GET/POST /api/projects/{id}/experiments/...` | 实验登记与完成 |

完整路径以 controller 为准；管理端仍保留运维归档与告警查询（`/api/admin/...`）。

## 评测与简历证据

版本化评测集：`src/main/resources/rag-eval/evidencelab-rag-eval-v1.json`

内置知识库（`src/main/resources/knowledge/`）覆盖：

- 12GB 约束下 LoRA vs QLoRA
- OOM 实验日志
- 不同数据规模的冲突论文结论
- 基线 README 配置
- 两项目相似词泄漏样本

验收门槛（由 `EvidenceLabAcceptanceTests` 与 `scripts/run-evidencelab-eval.sh` 固化）：

| 指标 | 门槛 |
| --- | --- |
| Intent accuracy | ≥ 0.90 |
| Recall@5 | ≥ 0.85 |
| Claim-source support rate | ≥ 0.95 |
| Structured output success rate | ≥ 0.98 |
| Cross-project leakage count | = 0 |
| Task recovery scenarios passed | = 4 |

最新跑分见：[docs/evaluation/evidencelab-v1-results.md](docs/evaluation/evidencelab-v1-results.md)。

## 配置要点

核心配置在 `application.yml` 的 `mindbridge:` 节点（历史前缀，语义已是 EvidenceLab）：

```yaml
mindbridge:
  rag-eval:
    dataset: classpath:rag-eval/evidencelab-rag-eval-v1.json
  research:
    source-storage-dir: ${EVIDENCELAB_SOURCE_DIR:./data/research-sources}
  task:
    max-attempts: 2
```

常用环境变量：

| 变量 | 默认/说明 |
| --- | --- |
| `AI_PROVIDER` | `openai` 或 `ollama` |
| `OPENAI_API_KEY` | OpenAI 兼容密钥 |
| `USE_CHROMA` | 是否启用向量库 |
| `CHROMA_COLLECTION` | 默认 `evidencelab_knowledge` |
| `DB_URL` | H2 或 MySQL JDBC |

MySQL profile：`application-mysql.yml`（库名默认 `evidencelab`）。

## 文档

- [多 Agent 协作说明](docs/multi-agent-collaboration-copy.md)
- [RAG 技术说明](docs/rag-technical-guide.md)
- [本地/服务器 LoRA 微调与 Ollama 接入](docs/qwen25-7b-lora-finetune-guide.md)
- [四核重构计划（历史）](docs/superpowers/plans/2026-09-11-evidencelab-four-core-refactor.md)

## 许可证与包名说明

本阶段不迁移 Java package / Maven `groupId`（仍为 `com.mindbridge`），以避免大范围破坏性改名。对外产品名、镜像名、数据库默认名与文档统一为 **EvidenceLab**。
