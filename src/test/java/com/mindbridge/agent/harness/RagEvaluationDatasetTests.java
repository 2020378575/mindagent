package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.knowledge.eval.ResearchRagEvalCase;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class RagEvaluationDatasetTests {

    private static final String DATASET_PATH = "rag-eval/evidencelab-rag-eval-v1.json";

    @Test
    void versionedDatasetCoversResearchFixturesAndIntents() throws Exception {
        List<ResearchRagEvalCase> cases = loadCases();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(15);
        assertThat(cases).extracting(ResearchRagEvalCase::id).doesNotHaveDuplicates();
        assertThat(cases).extracting(ResearchRagEvalCase::question).doesNotHaveDuplicates();
        assertThat(cases).extracting(ResearchRagEvalCase::expectedIntent)
                .contains(
                        IntentType.RESEARCH_DECISION,
                        IntentType.EVIDENCE_QUERY,
                        IntentType.RESULT_REVIEW,
                        IntentType.GENERAL_CHAT);
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.id()).isNotBlank();
            assertThat(testCase.question()).isNotBlank();
            assertThat(testCase.expectedIntent()).isNotNull();
            assertThat(testCase.expectedSources()).isNotNull();
            assertThat(testCase.requiredClaims()).isNotNull();
            assertThat(testCase.opposingClaims()).isNotNull();
            assertThat(testCase.forbiddenClaims()).isNotNull();
        });

        Set<String> expectedSources = cases.stream()
                .flatMap(testCase -> testCase.expectedSources().stream())
                .collect(Collectors.toSet());
        assertThat(bundledKnowledgeSources()).containsAll(expectedSources);
        assertThat(bundledKnowledgeSources()).contains(
                "lora-vs-qlora-12gb.md",
                "oom-experiment-log.md",
                "paper-a-large-data.md",
                "paper-b-small-data.md",
                "baseline-readme.md",
                "project-alpha-notes.md",
                "project-beta-notes.md");
    }

    private List<ResearchRagEvalCase> loadCases() throws Exception {
        Resource resource = new ClassPathResource(DATASET_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return new ObjectMapper().readValue(inputStream, new TypeReference<>() {
            });
        }
    }

    private Set<String> bundledKnowledgeSources() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:knowledge/*.md");
        return Arrays.stream(resources)
                .map(Resource::getFilename)
                .collect(Collectors.toSet());
    }
}
