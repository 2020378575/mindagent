package com.mindbridge.agent.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.repository.ResearchTaskCheckpointRepository;
import com.mindbridge.agent.repository.ResearchTaskRepository;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchTaskServiceTests {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 10L;

    @Mock
    private ResearchProjectService projectService;

    @Mock
    private ResearchTaskRepository taskRepository;

    @Mock
    private ResearchTaskCheckpointRepository checkpointRepository;

    private final Map<Long, ResearchTask> tasks = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private ResearchTaskService service;

    @BeforeEach
    void setUp() {
        ResearchTaskEventService eventService = new ResearchTaskEventService();
        when(projectService.requireOwnedProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(taskRepository.findByOwner_IdAndProject_IdAndIdempotencyKey(USER_ID, PROJECT_ID, "request-001"))
                .thenAnswer(invocation -> tasks.values().stream()
                        .filter(task -> "request-001".equals(task.getIdempotencyKey()))
                        .findFirst());
        when(taskRepository.save(any(ResearchTask.class))).thenAnswer(invocation -> {
            ResearchTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(ids.getAndIncrement());
            }
            tasks.put(task.getId(), task);
            return task;
        });

        service = new ResearchTaskService(
                projectService,
                taskRepository,
                checkpointRepository,
                eventService,
                new ObjectMapper());
    }

    @Test
    void createIsIdempotentForSameKey() {
        ResearchTask first = service.create(USER_ID, PROJECT_ID, new CreateResearchTaskRequest(
                "request-001", ResearchTaskType.DECISION, "LoRA or QLoRA?", null, null, null));
        ResearchTask duplicate = service.create(USER_ID, PROJECT_ID, new CreateResearchTaskRequest(
                "request-001", ResearchTaskType.DECISION, "LoRA or QLoRA?", null, null, null));

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(first.getStatus()).isEqualTo(ResearchTaskStatus.PENDING);
        assertThat(tasks).hasSize(1);
    }

    private ResearchProject project() {
        UserAccount owner = new UserAccount();
        owner.setUsername("user-" + USER_ID);
        owner.setDisplayName("User");
        owner.setPassword("secret");
        ResearchProject project = new ResearchProject();
        project.setId(PROJECT_ID);
        project.setOwner(owner);
        project.setName("Adapters");
        project.setObjective("Choose LoRA or QLoRA");
        return project;
    }
}
