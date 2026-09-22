package com.mindbridge.agent.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.dto.ResearchTaskEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ResearchTaskEventServiceTests {

    private static final String TASK_ID = "task-1";

    @Test
    void terminalEventCompletesStreamAndReleasesSink() {
        ResearchTaskEventService service = new ResearchTaskEventService();
        StepVerifier.create(service.stream(TASK_ID, List.of()))
                .then(() -> service.publish(event(ResearchTaskStatus.RUNNING)))
                .expectNextCount(1)
                .then(() -> service.publish(event(ResearchTaskStatus.SUCCEEDED)))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(service.activeSinkCount()).isZero();
    }

    private ResearchTaskEvent event(ResearchTaskStatus status) {
        return new ResearchTaskEvent(TASK_ID, status, null, 0, status.name(), Instant.now());
    }
}
