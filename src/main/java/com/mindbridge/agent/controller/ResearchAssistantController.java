package com.mindbridge.agent.controller;

import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.dto.ChatRequest;
import com.mindbridge.agent.dto.ChatStreamEvent;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.dto.ResearchTaskResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.ChatService;
import com.mindbridge.agent.service.task.ResearchTaskExecutor;
import com.mindbridge.agent.service.task.ResearchTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping(ResearchAssistantController.PATH)
/**
 * 研究助手入口。普通问答与正式决策任务分流，聊天不会静默创建决策。
 */
public class ResearchAssistantController {

    static final String PATH = "/api/research/assistant";

    private final ChatService chatService;
    private final ResearchTaskService researchTaskService;
    private final ResearchTaskExecutor researchTaskExecutor;

    public ResearchAssistantController(
            ChatService chatService,
            ResearchTaskService researchTaskService,
            ResearchTaskExecutor researchTaskExecutor
    ) {
        this.chatService = chatService;
        this.researchTaskService = researchTaskService;
        this.researchTaskExecutor = researchTaskExecutor;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamAnswer(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ChatRequest request
    ) {
        rejectAdmin(currentUser);
        return chatService.streamChat(currentUser.getId(), request);
    }

    @PostMapping("/decision-tasks")
    public ResearchTaskResponse createDecisionTask(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateDecisionTaskRequest request
    ) {
        rejectAdmin(currentUser);
        CreateResearchTaskRequest taskRequest = new CreateResearchTaskRequest(
                request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                        ? UUID.randomUUID().toString()
                        : request.idempotencyKey().trim(),
                ResearchTaskType.DECISION,
                request.question(),
                null,
                null,
                null);
        ResearchTask task = owned(() ->
                researchTaskService.create(currentUser.getId(), request.projectId(), taskRequest));
        if (task.getStatus() == ResearchTaskStatus.PENDING && task.getAttemptCount() == 0) {
            researchTaskExecutor.submit(task.getId());
        }
        return ResearchTaskResponse.from(task);
    }

    private void rejectAdmin(CurrentUser currentUser) {
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员账号只能查看后台记录，不能发起研究助手对话。");
        }
    }

    private ResearchTask owned(Supplier<ResearchTask> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    public record CreateDecisionTaskRequest(
            @NotNull Long projectId,
            @NotBlank @Size(max = 4000) String question,
            @Size(max = 120) String idempotencyKey
    ) {
    }
}
