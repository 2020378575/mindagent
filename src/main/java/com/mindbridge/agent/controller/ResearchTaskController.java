package com.mindbridge.agent.controller;

import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.dto.ResearchTaskEvent;
import com.mindbridge.agent.dto.ResearchTaskResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.task.ResearchTaskEventService;
import com.mindbridge.agent.service.task.ResearchTaskExecutor;
import com.mindbridge.agent.service.task.ResearchTaskService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping(ResearchTaskController.TASKS_PATH)
/**
 * 研究任务接口。创建后异步执行，SSE 只订阅事件。
 */
public class ResearchTaskController {

    static final String TASKS_PATH = ProjectController.PROJECTS_PATH + "/{projectId}/tasks";
    static final String TASK_PUBLIC_ID = "/{taskPublicId}";
    static final String EVENTS_PATH = TASK_PUBLIC_ID + "/events";
    static final String RETRY_PATH = TASK_PUBLIC_ID + "/retry";
    static final String CANCEL_PATH = TASK_PUBLIC_ID + "/cancel";

    private final ResearchTaskService researchTaskService;
    private final ResearchTaskExecutor researchTaskExecutor;
    private final ResearchTaskEventService researchTaskEventService;

    public ResearchTaskController(
            ResearchTaskService researchTaskService,
            ResearchTaskExecutor researchTaskExecutor,
            ResearchTaskEventService researchTaskEventService
    ) {
        this.researchTaskService = researchTaskService;
        this.researchTaskExecutor = researchTaskExecutor;
        this.researchTaskEventService = researchTaskEventService;
    }

    @GetMapping
    public List<ResearchTaskResponse> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId
    ) {
        return ownedList(() -> researchTaskService.list(currentUser.getId(), projectId)).stream()
                .map(ResearchTaskResponse::from)
                .toList();
    }

    @PostMapping
    public ResearchTaskResponse create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @Valid @RequestBody CreateResearchTaskRequest request
    ) {
        ResearchTask task = owned(() -> researchTaskService.create(currentUser.getId(), projectId, request));
        if (task.getStatus() == com.mindbridge.agent.domain.ResearchTaskStatus.PENDING
                && task.getAttemptCount() == 0) {
            researchTaskExecutor.submit(task.getId());
        }
        return ResearchTaskResponse.from(task);
    }

    @GetMapping(TASK_PUBLIC_ID)
    public ResearchTaskResponse get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable String taskPublicId
    ) {
        return ResearchTaskResponse.from(owned(() ->
                researchTaskService.requireOwnedTask(currentUser.getId(), projectId, taskPublicId)));
    }

    @GetMapping(value = EVENTS_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ResearchTaskEvent>> events(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable String taskPublicId
    ) {
        ResearchTask task = owned(() ->
                researchTaskService.requireOwnedTask(currentUser.getId(), projectId, taskPublicId));
        return researchTaskEventService.stream(taskPublicId, history(task));
    }

    @PostMapping(RETRY_PATH)
    public ResearchTaskResponse retry(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable String taskPublicId
    ) {
        ResearchTask task = owned(() ->
                researchTaskService.retry(currentUser.getId(), projectId, taskPublicId));
        researchTaskExecutor.submit(task.getId());
        return ResearchTaskResponse.from(task);
    }

    @PostMapping(CANCEL_PATH)
    public ResearchTaskResponse cancel(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable String taskPublicId
    ) {
        return ResearchTaskResponse.from(owned(() ->
                researchTaskService.cancel(currentUser.getId(), projectId, taskPublicId)));
    }

    private List<ResearchTaskEvent> history(ResearchTask task) {
        List<ResearchTaskEvent> events = new ArrayList<>();
        events.add(new ResearchTaskEvent(
                task.getPublicId(),
                task.getStatus(),
                task.getCurrentStage(),
                task.getProgressPercent(),
                "Current task state",
                task.getUpdatedAt()
        ));
        for (ResearchTaskCheckpoint checkpoint : researchTaskService.checkpoints(task.getId())) {
            events.add(new ResearchTaskEvent(
                    task.getPublicId(),
                    checkpoint.getStatus(),
                    checkpoint.getStage(),
                    task.getProgressPercent(),
                    checkpoint.getObservation(),
                    checkpoint.getCompletedAt()
            ));
        }
        return events;
    }

    private ResearchTask owned(Supplier<ResearchTask> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private List<ResearchTask> ownedList(Supplier<List<ResearchTask>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }
}
