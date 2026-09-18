package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.dto.ResearchTaskEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
/**
 * 任务事件总线。SSE 只订阅事件投影，断开连接不会取消任务。
 */
public class ResearchTaskEventService {

    private final Map<String, Sinks.Many<ResearchTaskEvent>> sinks = new ConcurrentHashMap<>();

    public void publish(ResearchTaskEvent event) {
        Sinks.Many<ResearchTaskEvent> sink = sinks.computeIfAbsent(
                event.taskPublicId(),
                key -> Sinks.many().multicast().onBackpressureBuffer());
        sink.tryEmitNext(event);
    }

    public Flux<ServerSentEvent<ResearchTaskEvent>> stream(
            String taskPublicId,
            List<ResearchTaskEvent> history
    ) {
        Sinks.Many<ResearchTaskEvent> sink = sinks.computeIfAbsent(
                taskPublicId,
                key -> Sinks.many().multicast().onBackpressureBuffer());
        Flux<ResearchTaskEvent> live = sink.asFlux()
                .filter(event -> taskPublicId.equals(event.taskPublicId()));
        return Flux.concat(Flux.fromIterable(history), live)
                .takeUntil(event -> isTerminal(event.status()))
                .map(event -> ServerSentEvent.builder(event).event("task").build())
                .timeout(Duration.ofHours(6), Flux.empty());
    }

    private boolean isTerminal(ResearchTaskStatus status) {
        return status == ResearchTaskStatus.SUCCEEDED
                || status == ResearchTaskStatus.FAILED
                || status == ResearchTaskStatus.CANCELLED
                || status == ResearchTaskStatus.WAITING_FOR_CONFIRMATION;
    }
}
