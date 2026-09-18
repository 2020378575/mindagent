package com.mindbridge.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.MemoryValidationStatus;
import com.mindbridge.agent.domain.ResearchMemoryItem;
import com.mindbridge.agent.domain.ResearchMemorySourceType;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ResearchMemoryItemRepository;
import com.mindbridge.agent.repository.ResearchProjectRepository;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchMemoryServiceTests {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_A = 11L;
    private static final Long PROJECT_B = 22L;

    @Mock
    private ResearchProjectService projectService;

    @Mock
    private ResearchProjectRepository projectRepository;

    @Mock
    private ResearchMemoryItemRepository memoryRepository;

    @Mock
    private ResearchMemoryChromaGateway chromaGateway;

    @Mock
    private ResearchWorkingMemoryService workingMemoryService;

    @Mock
    private ProjectRecentMemoryService recentMemoryService;

    private final Map<Long, ResearchMemoryItem> store = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private ResearchLongTermMemoryService longTermMemory;

    @BeforeEach
    void setUp() {
        lenient().when(projectRepository.findById(PROJECT_A)).thenReturn(Optional.of(project(PROJECT_A)));
        lenient().when(projectRepository.findById(PROJECT_B)).thenReturn(Optional.of(project(PROJECT_B)));
        lenient().when(projectService.requireOwnedProject(USER_ID, PROJECT_A)).thenReturn(project(PROJECT_A));
        lenient().when(memoryRepository.save(any(ResearchMemoryItem.class))).thenAnswer(invocation -> {
            ResearchMemoryItem item = invocation.getArgument(0);
            if (item.getId() == null) {
                item.setId(ids.getAndIncrement());
            }
            store.put(item.getId(), item);
            return item;
        });
        lenient().when(memoryRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(store.get(invocation.getArgument(0))));
        lenient().when(memoryRepository.findTop8ByProject_IdAndActiveTrueOrderByUpdatedAtDesc(anyLong()))
                .thenAnswer(invocation -> {
                    Long projectId = invocation.getArgument(0);
                    return store.values().stream()
                            .filter(item -> projectId.equals(item.projectId()))
                            .filter(ResearchMemoryItem::isActive)
                            .sorted((left, right) -> right.getUpdatedAt().compareTo(left.getUpdatedAt()))
                            .limit(8)
                            .toList();
                });
        lenient().when(chromaGateway.query(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        lenient().when(workingMemoryService.load(anyLong(), anyLong(), any()))
                .thenReturn(new ResearchWorkingMemory(null, PROJECT_A, null, null, List.of(), null, null));
        lenient().when(recentMemoryService.recentEvents(anyLong())).thenReturn(List.of());

        longTermMemory = new ResearchLongTermMemoryService(
                projectService,
                projectRepository,
                memoryRepository,
                chromaGateway,
                workingMemoryService,
                recentMemoryService,
                new ObjectMapper());
    }

    @Test
    void rememberValidatedPromotesConfirmedDecision() {
        ResearchMemoryItem promoted = longTermMemory.rememberValidated(
                new ValidatedResearchMemory(
                        PROJECT_A,
                        ResearchMemorySourceType.DECISION,
                        101L,
                        MemoryValidationStatus.CONFIRMED,
                        "QLoRA reduced peak memory under the project constraints",
                        List.of(55L)));

        assertThat(promoted.getValidationStatus()).isEqualTo(MemoryValidationStatus.CONFIRMED);
        assertThat(promoted.isActive()).isTrue();
        assertThat(promoted.projectId()).isEqualTo(PROJECT_A);
        verify(chromaGateway).mirror(promoted);
    }

    @Test
    void rejectsRefutedPromotion() {
        assertThatThrownBy(() -> new ValidatedResearchMemory(
                PROJECT_A,
                ResearchMemorySourceType.DECISION,
                101L,
                MemoryValidationStatus.REFUTED,
                "should fail",
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refuted evidence cannot be promoted");
    }

    @Test
    void markRefutedKeepsAuditableButInactive() {
        ResearchMemoryItem promoted = longTermMemory.rememberValidated(
                new ValidatedResearchMemory(
                        PROJECT_A,
                        ResearchMemorySourceType.EXPERIMENT_REVIEW,
                        202L,
                        MemoryValidationStatus.EXPERIMENT_VERIFIED,
                        "Batch size 8 fits 12GB",
                        List.of(1L)));

        longTermMemory.markRefuted(promoted.getId(), 9L);

        assertThat(memoryRepository.findById(promoted.getId())).get()
                .satisfies(memory -> {
                    assertThat(memory.getValidationStatus()).isEqualTo(MemoryValidationStatus.REFUTED);
                    assertThat(memory.isActive()).isFalse();
                    assertThat(memory.getReviewId()).isEqualTo(9L);
                });
        verify(chromaGateway).delete(promoted.getId());
    }

    @Test
    void recallDoesNotCrossProjectsAndSkipsRefuted() {
        longTermMemory.rememberValidated(new ValidatedResearchMemory(
                PROJECT_A,
                ResearchMemorySourceType.DECISION,
                1L,
                MemoryValidationStatus.CONFIRMED,
                "alpha unique memory",
                List.of()));
        ResearchMemoryItem beta = longTermMemory.rememberValidated(new ValidatedResearchMemory(
                PROJECT_B,
                ResearchMemorySourceType.DECISION,
                2L,
                MemoryValidationStatus.CONFIRMED,
                "beta unique memory",
                List.of()));
        ResearchMemoryItem refuted = longTermMemory.rememberValidated(new ValidatedResearchMemory(
                PROJECT_A,
                ResearchMemorySourceType.DECISION,
                3L,
                MemoryValidationStatus.CONFIRMED,
                "will be refuted",
                List.of()));
        longTermMemory.markRefuted(refuted.getId(), 77L);

        List<ResearchMemoryMatch> matches = longTermMemory.recall(PROJECT_A, "memory");
        assertThat(matches).extracting(ResearchMemoryMatch::projectId).containsOnly(PROJECT_A);
        assertThat(matches).extracting(ResearchMemoryMatch::summary)
                .contains("alpha unique memory")
                .doesNotContain("beta unique memory")
                .doesNotContain("will be refuted");
        assertThat(store.get(beta.getId()).isActive()).isTrue();
    }

    private ResearchProject project(Long id) {
        UserAccount owner = new UserAccount();
        owner.setUsername("user-" + id);
        owner.setDisplayName("User");
        owner.setPassword("secret");
        ResearchProject project = new ResearchProject();
        project.setId(id);
        project.setOwner(owner);
        project.setName("Project " + id);
        project.setObjective("Objective");
        return project;
    }
}
