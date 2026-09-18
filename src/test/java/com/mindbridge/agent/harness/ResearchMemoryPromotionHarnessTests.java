package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.MemoryValidationStatus;
import com.mindbridge.agent.domain.ResearchMemoryItem;
import com.mindbridge.agent.domain.ResearchMemorySourceType;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ResearchMemoryItemRepository;
import com.mindbridge.agent.repository.ResearchProjectRepository;
import com.mindbridge.agent.service.memory.ProjectRecentMemoryService;
import com.mindbridge.agent.service.memory.ResearchLongTermMemoryService;
import com.mindbridge.agent.service.memory.ResearchMemoryBundle;
import com.mindbridge.agent.service.memory.ResearchMemoryChromaGateway;
import com.mindbridge.agent.service.memory.ResearchMemoryMatch;
import com.mindbridge.agent.service.memory.ResearchProjectEvent;
import com.mindbridge.agent.service.memory.ResearchWorkingMemory;
import com.mindbridge.agent.service.memory.ResearchWorkingMemoryService;
import com.mindbridge.agent.service.memory.ValidatedResearchMemory;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.time.Instant;
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
class ResearchMemoryPromotionHarnessTests {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_A = 11L;
    private static final Long PROJECT_B = 22L;
    private static final Long TASK_ID = 88L;

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
        lenient().when(projectRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.of(project(invocation.getArgument(0))));
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
                            .toList();
                });
        lenient().when(workingMemoryService.load(USER_ID, PROJECT_A, TASK_ID))
                .thenReturn(new ResearchWorkingMemory(
                        TASK_ID, PROJECT_A, "CRITIC", "LoRA or QLoRA?", List.of(1L, 2L), "needs more VRAM evidence", null));
        lenient().when(recentMemoryService.recentEvents(PROJECT_A))
                .thenReturn(List.of("SOURCE_READY: adapter-notes.md", "DECISION_DRAFT: prefer QLoRA"));

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
    void onlyValidatedCommandCreatesActiveMemoryAndLoadStaysProjectScoped() {
        ResearchMemoryItem promoted = longTermMemory.rememberValidated(new ValidatedResearchMemory(
                PROJECT_A,
                ResearchMemorySourceType.DECISION,
                99L,
                MemoryValidationStatus.CONFIRMED,
                "QLoRA reduced peak memory under the project constraints",
                List.of(10L)));
        assertThat(promoted.getValidationStatus()).isEqualTo(MemoryValidationStatus.CONFIRMED);
        assertThat(promoted.isActive()).isTrue();

        longTermMemory.rememberValidated(new ValidatedResearchMemory(
                PROJECT_B,
                ResearchMemorySourceType.DECISION,
                100L,
                MemoryValidationStatus.CONFIRMED,
                "foreign project should not appear",
                List.of()));
        longTermMemory.markRefuted(promoted.getId(), 5L);
        assertThat(store.get(promoted.getId()).getValidationStatus()).isEqualTo(MemoryValidationStatus.REFUTED);
        assertThat(store.get(promoted.getId()).isActive()).isFalse();

        when(chromaGateway.query(eq(PROJECT_A), anyString(), anyInt())).thenReturn(List.of(
                new ResearchMemoryMatch(
                        999L,
                        PROJECT_B,
                        "leaked foreign chroma hit",
                        MemoryValidationStatus.CONFIRMED,
                        0.99)
        ));

        ResearchMemoryItem stillActive = longTermMemory.rememberValidated(new ValidatedResearchMemory(
                PROJECT_A,
                ResearchMemorySourceType.EXPERIMENT_REVIEW,
                44L,
                MemoryValidationStatus.EXPERIMENT_VERIFIED,
                "batch=8 verified on 12GB",
                List.of(3L)));

        ResearchMemoryBundle bundle = longTermMemory.load(USER_ID, PROJECT_A, TASK_ID, "QLoRA memory");

        assertThat(bundle.working().taskId()).isEqualTo(TASK_ID);
        assertThat(bundle.recentProjectEvents()).hasSize(2);
        assertThat(bundle.longTermMemories())
                .extracting(ResearchMemoryMatch::projectId)
                .containsOnly(PROJECT_A);
        assertThat(bundle.longTermMemories())
                .extracting(ResearchMemoryMatch::summary)
                .contains("batch=8 verified on 12GB")
                .doesNotContain("foreign project should not appear")
                .doesNotContain("leaked foreign chroma hit")
                .doesNotContain("QLoRA reduced peak memory under the project constraints");
        assertThat(stillActive.isActive()).isTrue();

        longTermMemory.rememberProjectEvent(PROJECT_A, new ResearchProjectEvent(
                "EXPERIMENT_LOGGED", "epoch2 loss dropped", 44L, Instant.now()));
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
