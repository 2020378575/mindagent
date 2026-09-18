package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.knowledge.eval.RagEndToEndCaseResult;
import com.mindbridge.agent.service.knowledge.eval.RagEvalReport;
import com.mindbridge.agent.service.knowledge.eval.RagEvaluationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RagEvaluationHarnessTests {

    private RagEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        ScriptedAiClient aiClient = new ScriptedAiClient();
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(knowledgeService.retrieve(anyString(), anyInt())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0, String.class);
            return fixtureHits(question);
        });

        evaluationService = new RagEvaluationService(
                knowledgeService,
                aiClient,
                new IntentClassifier(aiClient),
                new ObjectMapper());
    }

    @Test
    void evaluatesResearchHarnessScenarios() {
        RagEvalReport report = evaluationService.evaluate("classpath:harness/research-harness-scenarios.json", 5);

        assertThat(report.totalCases()).isEqualTo(4);
        assertThat(report.passedCases()).isEqualTo(4);
        assertThat(report.failedCases()).isZero();
        assertThat(report.cases()).allSatisfy(testCase -> {
            assertThat(testCase.passed()).isTrue();
            assertThat(testCase.failures()).isEmpty();
            assertThat(testCase.actualIntent()).isNotBlank();
            assertThat(testCase.answer()).isNotBlank();
        });
        assertThat(report.metrics().intentAccuracy()).isEqualTo(1.0);
        assertThat(report.metrics().recallAtFive()).isEqualTo(1.0);
    }

    @Test
    void summaryIncludesEvidenceLabMetrics() {
        RagEvalReport report = evaluationService.evaluate("classpath:harness/research-harness-scenarios.json", 5);

        assertThat(evaluationService.formatSummary(report))
                .contains("passed=4")
                .contains("failed=0")
                .contains("intentAccuracy=");
        assertThat(report.cases())
                .extracting(RagEndToEndCaseResult::expectedIntent)
                .contains("RESEARCH_DECISION", "RESULT_REVIEW", "EVIDENCE_QUERY", "GENERAL_CHAT");
    }

    static List<SearchResult> fixtureHits(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<SearchResult> hits = new ArrayList<>();
        if (normalized.contains("12gb") || normalized.contains("qlora") || normalized.contains("lora")) {
            hits.add(SearchResult.of(
                    1L,
                    "lora-vs-qlora-12gb.md",
                    "Prefer QLoRA when VRAM is capped at 12GB. QLoRA with NF4 reduces peak memory versus LoRA.",
                    0.97));
        }
        if (normalized.contains("run-019") || normalized.contains("oom") || normalized.contains("峰值")
                || normalized.contains("实验日志") || normalized.contains("是否支持")) {
            hits.add(SearchResult.of(
                    2L,
                    "oom-experiment-log.md",
                    "event=CUDA out of memory peak_memory_gb=13.8 LoRA without quantization exceeded 12GB. next_action=retry with QLoRA NF4 and batch=4",
                    0.96));
        }
        if (normalized.contains("readme") || normalized.contains("量化配置") || normalized.contains("学习率")
                || normalized.contains("batch_size") || normalized.contains("基线")) {
            hits.add(SearchResult.of(
                    3L,
                    "baseline-readme.md",
                    "method: QLoRA quantization: NF4 learning_rate: 2e-4 batch_size: 4 max_seq_len: 1024 under 12GB",
                    0.95));
        }
        if (normalized.contains("论文 a") || normalized.contains("大数据") || normalized.contains("100k")) {
            hits.add(SearchResult.of(
                    4L,
                    "paper-a-large-data.md",
                    "On datasets larger than 100k examples LoRA can outperform QLoRA on accuracy when VRAM is not constrained.",
                    0.94));
        }
        if (normalized.contains("论文 b") || normalized.contains("小数据") || normalized.contains("10k")) {
            hits.add(SearchResult.of(
                    5L,
                    "paper-b-small-data.md",
                    "On datasets smaller than 10k examples QLoRA remains competitive under memory constraints.",
                    0.93));
        }
        if (normalized.contains("alpha")) {
            hits.add(SearchResult.of(
                    6L,
                    "project-alpha-notes.md",
                    "Project-specific token: ALPHA-ONLY-SIGNAL shared terms adapter LoRA QLoRA",
                    0.92));
        }
        if (normalized.contains("beta")) {
            hits.add(SearchResult.of(
                    7L,
                    "project-beta-notes.md",
                    "Project-specific token: BETA-ONLY-SIGNAL shared terms adapter LoRA QLoRA",
                    0.91));
        }
        if (normalized.contains("两篇") || normalized.contains("取舍") || normalized.contains("数据规模")) {
            hits.add(SearchResult.of(
                    4L,
                    "paper-a-large-data.md",
                    "On datasets larger than 100k examples LoRA may be preferred for accuracy.",
                    0.94));
            hits.add(SearchResult.of(
                    5L,
                    "paper-b-small-data.md",
                    "On datasets smaller than 10k examples QLoRA is a strong default.",
                    0.93));
        }
        if (normalized.contains("nf4") && hits.isEmpty()) {
            hits.add(SearchResult.of(
                    1L,
                    "lora-vs-qlora-12gb.md",
                    "Quantization: NF4 for QLoRA under 12GB.",
                    0.9));
            hits.add(SearchResult.of(
                    3L,
                    "baseline-readme.md",
                    "quantization: NF4 learning_rate: 2e-4",
                    0.89));
        }
        return hits;
    }
}
