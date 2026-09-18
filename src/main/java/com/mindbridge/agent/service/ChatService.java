package com.mindbridge.agent.service;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.domain.ProjectStatus;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.ChatRequest;
import com.mindbridge.agent.dto.ChatStreamEvent;
import com.mindbridge.agent.dto.CreateResearchProjectRequest;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.repository.ChatSessionRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentRuntimeService;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
/**
 * 临时聊天入口。研究任务由 ResearchTask handlers 驱动；此处仍走同一套研究 Agent 环。
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String DEFAULT_PROJECT_NAME = "Default project";
    private static final String DEFAULT_PROJECT_OBJECTIVE =
            "Temporary workspace for existing conversations until the research workspace is ready.";

    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MindBridgeProperties properties;
    private final PrivacySanitizer privacySanitizer;
    private final ShortTermMemoryService shortTermMemoryService;
    private final UserProfileMemoryService userProfileMemoryService;
    private final AgentRuntimeService agentRuntimeService;
    private final AgentRunTraceService agentRunTraceService;
    private final ResearchProjectService researchProjectService;
    private final AiClient aiClient;

    public ChatService(
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            MindBridgeProperties properties,
            PrivacySanitizer privacySanitizer,
            ShortTermMemoryService shortTermMemoryService,
            UserProfileMemoryService userProfileMemoryService,
            AgentRuntimeService agentRuntimeService,
            AgentRunTraceService agentRunTraceService,
            ResearchProjectService researchProjectService,
            AiClient aiClient
    ) {
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.properties = properties;
        this.privacySanitizer = privacySanitizer;
        this.shortTermMemoryService = shortTermMemoryService;
        this.userProfileMemoryService = userProfileMemoryService;
        this.agentRuntimeService = agentRuntimeService;
        this.agentRunTraceService = agentRunTraceService;
        this.researchProjectService = researchProjectService;
        this.aiClient = aiClient;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(Long userId, ChatRequest request) {
        return Mono.fromCallable(() -> prepare(userId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::streamPrepared)
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(null, "服务暂时不可用：" + exception.getMessage()))));
    }

    private PreparedConversation prepare(Long userId, ChatRequest request) {
        String input = request.message().trim();
        String modelInput = privacySanitizer.sanitize(input);
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatSession session = resolveSession(user, request.sessionId(), input);
        Instant startedAt = Instant.now();
        AgentContext context = new AgentContext(
                null,
                user.getId(),
                session.getProject() == null ? null : session.getProject().getId(),
                input,
                modelInput);
        AgentRunResult agentRun = agentRuntimeService.run(context);
        Instant completedAt = Instant.now();
        ChatMessage userMessage = saveMessage(user, session, MessageRole.USER, input);
        agentRunTraceService.saveRun(user, session, userMessage, input, agentRun, startedAt, completedAt);
        rememberUserProfile(user, session, input, agentRun.memoryBrief());

        List<AiMessage> messages = agentRun.responseMessages().isEmpty()
                ? buildMessages(user, agentRun.intent(), agentRun.retrievedEvidence(), List.of())
                : agentRun.responseMessages();
        return new PreparedConversation(user, session, agentRun.intent(), RiskLevel.LOW, messages);
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> streamPrepared(PreparedConversation prepared) {
        StringBuilder assistantReply = new StringBuilder();
        Flux<ServerSentEvent<ChatStreamEvent>> meta = Flux.just(event(
                "meta",
                ChatStreamEvent.meta(prepared.session().getPublicId())));

        Flux<ServerSentEvent<ChatStreamEvent>> tokens = aiClient.stream(prepared.messages())
                .doOnNext(assistantReply::append)
                .map(token -> event("token", ChatStreamEvent.token(prepared.session().getPublicId(), token)))
                .timeout(Duration.ofSeconds(45))
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(prepared.session().getPublicId(), "模型响应超时或失败，请稍后重试。"))))
                .switchIfEmpty(Flux.just(event(
                        "error",
                        ChatStreamEvent.error(prepared.session().getPublicId(), "模型没有返回内容，请稍后重试。"))));

        Mono<ServerSentEvent<ChatStreamEvent>> done = Mono.fromCallable(() -> {
            if (!assistantReply.isEmpty()) {
                saveMessage(prepared.user(), prepared.session(), MessageRole.ASSISTANT, assistantReply.toString());
            }
            return event("done", ChatStreamEvent.done(prepared.session().getPublicId()));
        }).subscribeOn(Schedulers.boundedElastic());

        return meta.concatWith(tokens).concatWith(done);
    }

    private ChatSession resolveSession(UserAccount user, String publicId, String input) {
        if (publicId != null && !publicId.isBlank()) {
            return chatSessionRepository.findByPublicIdAndUser_Id(publicId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        }
        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        session.setProject(resolveProject(user));
        session.setTitle(input.length() > 36 ? input.substring(0, 36) : input);
        return chatSessionRepository.save(session);
    }

    private ResearchProject resolveProject(UserAccount user) {
        return researchProjectService.list(user.getId()).stream()
                .filter(project -> project.getStatus() == ProjectStatus.ACTIVE)
                .findFirst()
                .orElseGet(() -> researchProjectService.create(
                        user.getId(),
                        new CreateResearchProjectRequest(
                                DEFAULT_PROJECT_NAME,
                                DEFAULT_PROJECT_OBJECTIVE,
                                null)));
    }

    private ChatMessage saveMessage(UserAccount user, ChatSession session, MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUser(user);
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);
        session.touch();
        chatSessionRepository.save(session);
        shortTermMemoryService.append(session.getPublicId(), role, content);
        return message;
    }

    private void rememberUserProfile(UserAccount user, ChatSession session, String input, String memoryBrief) {
        try {
            userProfileMemoryService.rememberUserInput(user, session, input, memoryBrief);
        } catch (Exception exception) {
            log.debug("User profile memory update skipped: {}", exception.getMessage());
        }
    }

    private List<AiMessage> buildMessages(
            UserAccount user,
            IntentType intent,
            List<SearchResult> retrieved,
            List<AiMessage> history
    ) {
        String context = String.join("\n\n", retrieved.stream()
                .map(result -> "- [" + result.source() + "] " + result.content())
                .toList());
        List<AiMessage> messages = new ArrayList<>();
        messages.add(PromptTemplates.answerSystemPrompt(intent, context, user.getDisplayName()));
        int limit = Math.max(2, properties.getChat().getHistoryLimit() * 2);
        history.stream()
                .skip(Math.max(0, history.size() - limit))
                .forEach(messages::add);
        return messages;
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private record PreparedConversation(
            UserAccount user,
            ChatSession session,
            IntentType intent,
            RiskLevel riskLevel,
            List<AiMessage> messages
    ) {
    }
}
