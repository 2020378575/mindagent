package com.mindbridge.agent.service.knowledge.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class RagEvaluationService {

    private final KnowledgeService knowledgeService;
    private final AiClient aiClient;
    private final IntentClassifier intentClassifier;
    private final ObjectMapper objectMapper;
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    public RagEvaluationService(
            KnowledgeService knowledgeService,
            AiClient aiClient,
            IntentClassifier intentClassifier,
            ObjectMapper objectMapper
    ) {
        this.knowledgeService = knowledgeService;
        this.aiClient = aiClient;
        this.intentClassifier = intentClassifier;
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public RagEvalReport evaluate(String datasetLocation, int topK) {
        return evaluate(datasetLocation, topK, "unknown", 0L, 0);
    }

    public RagEvalReport evaluate(
            String datasetLocation,
            int topK,
            String gitCommit,
            long crossProjectLeakageCount,
            int taskRecoveryScenariosPassed
    ) {
        List<ResearchRagEvalCase> cases = loadDataset(datasetLocation);
        List<RagEndToEndCaseResult> results = cases.stream()
                .map(testCase -> buildCase(testCase, topK))
                .toList();
        long passedCases = results.stream().filter(RagEndToEndCaseResult::passed).count();
        EvidenceLabMetrics metrics = buildMetrics(
                datasetLocation,
                gitCommit,
                results,
                crossProjectLeakageCount,
                taskRecoveryScenariosPassed);
        return new RagEvalReport(
                Instant.now(),
                datasetLocation,
                topK,
                results.size(),
                passedCases,
                results.size() - passedCases,
                metrics,
                results);
    }

    public void writeReport(RagEvalReport report, String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(outputPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writeValue(path.toFile(), report);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write EvidenceLab eval report: " + outputPath, exception);
        }
    }

    public void writeMetrics(EvidenceLabMetrics metrics, String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(outputPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writeValue(path.toFile(), metrics);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write EvidenceLab metrics: " + outputPath, exception);
        }
    }

    public String formatSummary(RagEvalReport report) {
        EvidenceLabMetrics metrics = report.metrics();
        return """
                EvidenceLab evaluation completed.
                dataset=%s
                cases=%d
                passed=%d
                failed=%d
                topK=%d
                intentAccuracy=%.4f
                recallAtFive=%.4f
                claimSourceSupportRate=%.4f
                structuredOutputSuccessRate=%.4f
                crossProjectLeakageCount=%d
                taskRecoveryScenariosPassed=%d
                """.formatted(
                report.dataset(),
                report.totalCases(),
                report.passedCases(),
                report.failedCases(),
                report.topK(),
                metrics.intentAccuracy(),
                metrics.recallAtFive(),
                metrics.claimSourceSupportRate(),
                metrics.structuredOutputSuccessRate(),
                metrics.crossProjectLeakageCount(),
                metrics.taskRecoveryScenariosPassed());
    }

    private EvidenceLabMetrics buildMetrics(
            String datasetLocation,
            String gitCommit,
            List<RagEndToEndCaseResult> results,
            long crossProjectLeakageCount,
            int taskRecoveryScenariosPassed
    ) {
        int total = results.size();
        long intentHits = results.stream()
                .filter(result -> normalize(result.expectedIntent()).equals(normalize(result.actualIntent())))
                .count();
        long recallHits = results.stream()
                .filter(result -> result.failures().stream().noneMatch(failure -> failure.startsWith("missing expected source:")))
                .count();
        long claimHits = results.stream()
                .filter(result -> result.failures().stream().noneMatch(failure ->
                        failure.startsWith("missing required claim:")
                                || failure.startsWith("opposing claim missing grounding:")
                                || failure.startsWith("forbidden claim present:")))
                .count();
        long structuredHits = results.stream()
                .filter(result -> result.failures().stream().noneMatch(failure -> failure.startsWith("structured:")))
                .count();
        long passed = results.stream().filter(RagEndToEndCaseResult::passed).count();
        return new EvidenceLabMetrics(
                datasetVersion(datasetLocation),
                gitCommit == null || gitCommit.isBlank() ? "unknown" : gitCommit,
                ratio(intentHits, total),
                ratio(recallHits, total),
                ratio(claimHits, total),
                ratio(structuredHits, total),
                crossProjectLeakageCount,
                taskRecoveryScenariosPassed,
                total,
                passed);
    }

    private RagEndToEndCaseResult buildCase(ResearchRagEvalCase testCase, int topK) {
        List<SearchResult> retrieved = knowledgeService.retrieve(testCase.question(), Math.max(topK, 5));
        List<String> retrievedContexts = retrieved.stream().map(SearchResult::content).toList();
        List<String> retrievedSources = retrieved.stream().map(SearchResult::source).distinct().toList();
        String actualIntent = actualIntent(testCase.question());
        String answer = generateAnswer(testCase.question(), retrievedContexts);
        List<String> failures = evaluateAssertions(testCase, actualIntent, retrievedSources, retrievedContexts, answer);
        return new RagEndToEndCaseResult(
                testCase.id(),
                testCase.question(),
                testCase.expectedIntent() == null ? "" : testCase.expectedIntent().name(),
                actualIntent,
                retrievedSources,
                retrievedContexts,
                answer,
                failures.isEmpty(),
                failures);
    }

    private String generateAnswer(String question, List<String> retrievedContexts) {
        String context = retrievedContexts.isEmpty()
                ? "No retrieved context."
                : String.join("\n\n---\n\n", retrievedContexts);
        return aiClient.complete(List.of(
                AiMessage.system("""
                        You are EvidenceLab's research answer generator for evaluation samples.
                        Answer from retrieved evidence only. Prefer concrete claims about methods,
                        VRAM, dataset size, and experiment outcomes. Keep answers under 180 words.
                        Do not invent citations, Excel workflows, or MCP internals.
                        """),
                AiMessage.user("""
                        Question:
                        %s

                        Retrieved context:
                        %s
                        """.formatted(question, context))
        )).trim();
    }

    private List<ResearchRagEvalCase> loadDataset(String datasetLocation) {
        try {
            Resource resource = resourceLoader.getResource(datasetLocation);
            try (InputStream inputStream = resource.getInputStream()) {
                return objectMapper.readValue(inputStream, new TypeReference<>() {
                });
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to load EvidenceLab evaluation dataset: " + datasetLocation, exception);
        }
    }

    private String actualIntent(String question) {
        try {
            IntentType intent = intentClassifier.classify(question).intent();
            return intent == null ? "" : intent.name();
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> evaluateAssertions(
            ResearchRagEvalCase testCase,
            String actualIntent,
            List<String> retrievedSources,
            List<String> retrievedContexts,
            String answer
    ) {
        List<String> failures = new ArrayList<>();
        String expectedIntent = testCase.expectedIntent() == null ? "" : testCase.expectedIntent().name();
        if (!expectedIntent.isBlank() && !normalize(expectedIntent).equals(normalize(actualIntent))) {
            failures.add("intent expected=%s actual=%s".formatted(expectedIntent, actualIntent));
        }
        requireSources(failures, testCase.expectedSources(), retrievedSources);
        String joined = joined(retrievedContexts) + "\n" + safe(answer);
        requireClaims(failures, "missing required claim:", testCase.requiredClaims(), joined);
        requireClaims(failures, "opposing claim missing grounding:", testCase.opposingClaims(), joined);
        forbidClaims(failures, testCase.forbiddenClaims(), answer);
        if (answer == null || answer.isBlank()) {
            failures.add("structured: empty answer");
        } else if (answer.length() < 8) {
            failures.add("structured: answer too short");
        }
        return failures;
    }

    private void requireSources(List<String> failures, List<String> expectedSources, List<String> actualSources) {
        for (String expected : safeList(expectedSources)) {
            boolean matched = actualSources.stream().anyMatch(source -> containsNormalized(source, expected));
            if (!matched) {
                failures.add("missing expected source: " + expected);
            }
        }
    }

    private void requireClaims(List<String> failures, String prefix, List<String> claims, String value) {
        for (String claim : safeList(claims)) {
            if (!containsNormalized(value, claim)) {
                failures.add(prefix + " " + claim);
            }
        }
    }

    private void forbidClaims(List<String> failures, List<String> forbiddenClaims, String answer) {
        for (String claim : safeList(forbiddenClaims)) {
            if (containsNormalized(answer, claim)) {
                failures.add("forbidden claim present: " + claim);
            }
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String joined(List<String> values) {
        return String.join("\n", values == null ? List.of() : values);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean containsNormalized(String value, String expected) {
        if (value == null || expected == null || expected.isBlank()) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private double ratio(long hits, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return (double) hits / (double) total;
    }

    private String datasetVersion(String datasetLocation) {
        if (datasetLocation == null) {
            return "unknown";
        }
        int slash = Math.max(datasetLocation.lastIndexOf('/'), datasetLocation.lastIndexOf('\\'));
        return slash >= 0 ? datasetLocation.substring(slash + 1) : datasetLocation;
    }
}
