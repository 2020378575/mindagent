package com.mindbridge.agent.service.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import com.mindbridge.agent.service.agent.DecisionDraft;
import com.mindbridge.agent.service.agent.DecisionOption;
import com.mindbridge.agent.service.project.ResearchProjectService;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DecisionValidationServiceTests {

    private static final Long PROJECT_A = 11L;
    private static final Long PROJECT_B = 22L;

    @Mock
    private ResearchProjectService projectService;

    @Mock
    private ResearchTaskService taskService;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    private DecisionValidationService validator;

    @BeforeEach
    void setUp() {
        validator = new DecisionValidationService(
                projectService,
                taskService,
                chunkRepository,
                new ObjectMapper());
    }

    @Test
    void rejectsCitationFromForeignProject() {
        when(chunkRepository.findById(100L)).thenReturn(Optional.of(chunk(100L, PROJECT_B, "foreign")));

        DecisionDraft draft = draft(List.of(100L), List.of());

        assertThatThrownBy(() -> validator.validateOrThrow(PROJECT_A, draft))
                .isInstanceOf(DecisionValidationException.class)
                .hasMessageContaining("citation");
    }

    @Test
    void acceptsOwnedCitationsWithGapsLoweringConfidence() {
        when(chunkRepository.findById(1L)).thenReturn(Optional.of(chunk(1L, PROJECT_A, "local")));

        DecisionDraft draft = new DecisionDraft(
                "LoRA or QLoRA?",
                List.of(new DecisionOption("QLoRA", "lower memory", 0.8)),
                "QLoRA",
                "fits 12GB",
                List.of(1L),
                List.of(),
                List.of("missing multi-GPU ablation"),
                "one epoch on 12GB",
                "peak memory < 12GB",
                0.7);

        DecisionValidationResult result = validator.validate(PROJECT_A, draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.supporting()).extracting(ValidatedCitation::chunkId).containsExactly(1L);
    }

    @Test
    void rejectsSameChunkAsSupportAndOppose() {
        when(chunkRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.of(chunk(invocation.getArgument(0), PROJECT_A, "local")));

        DecisionDraft draft = draft(List.of(1L), List.of(1L));

        DecisionValidationResult result = validator.validate(PROJECT_A, draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors().stream().anyMatch(error -> error.contains("supporting and opposing"))).isTrue();
    }

    private DecisionDraft draft(List<Long> supporting, List<Long> opposing) {
        return new DecisionDraft(
                "question",
                List.of(),
                "recommendation",
                "rationale",
                supporting,
                opposing,
                List.of(),
                "min experiment",
                "success",
                0.6);
    }

    private KnowledgeChunk chunk(Long id, Long projectId, String source) {
        UserAccount owner = new UserAccount();
        owner.setUsername("u");
        owner.setPassword("p");
        ResearchProject project = new ResearchProject();
        project.setId(projectId);
        project.setOwner(owner);
        project.setName("p");
        project.setObjective("o");
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setProject(project);
        chunk.setSource(source);
        chunk.setContent("content-" + id);
        chunk.setStartOffset(0);
        chunk.setEndOffset(10);
        return chunk;
    }
}
