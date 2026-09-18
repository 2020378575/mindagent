# EvidenceLab 多 Agent 协作说明

EvidenceLab 把一轮研究输入拆成有限步协作：先装载项目上下文与记忆，再路由意图，随后按需检索证据、审查冲突观点，最后生成决策草稿或资料问答回复。研究者端看到的是连贯对话或任务进度，后台保留检查点与轨迹。

## 协作链路

```text
ResearchContextAgent
-> SupervisorAgent
-> EvidenceAgent
-> EvidenceCriticAgent
-> DecisionAgent / ResearchAssistantAgent
```

`AgentRuntimeService` 按固定顺序调度，最多 8 步。下一步由代码状态决定，不由模型自由挑选下一个 Agent。

## 意图与路径

- `GENERAL_CHAT`：轻量问答，不强制进入证据链。
- `EVIDENCE_QUERY`：资料出处、配置与引用问答。
- `RESEARCH_DECISION`：方案比较与取舍，产出结构化决策草稿。
- `RESULT_REVIEW`：对照实验日志复核先前结论。

## 为什么拆 Agent

单 prompt 很难同时做好边界控制、证据引用、冲突审查与可恢复执行。拆分后：

1. 检索与生成解耦，引用可回到 chunk / 页码 / 标题。
2. Critic 专门处理支持与反对证据，避免一边倒幻觉。
3. 长流程落到 ResearchTask 状态机，SSE 断线不丢结果。

## 迁移说明

历史仓库产品名曾为 MindBridge。本阶段对外统一为 EvidenceLab；Java 包名 `com.mindbridge.agent` 与配置前缀 `mindbridge.*` 暂未迁移，仅作实现层兼容。
