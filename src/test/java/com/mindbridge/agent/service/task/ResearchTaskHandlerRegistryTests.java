package com.mindbridge.agent.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.ResearchTaskType;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-handler-registry;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
class ResearchTaskHandlerRegistryTests {

    @Autowired
    private ResearchTaskHandlerRegistry registry;

    @Test
    void everyResearchTaskTypeResolvesToExactlyOneHandler() {
        Set<ResearchTaskType> registered = EnumSet.noneOf(ResearchTaskType.class);
        for (ResearchTaskType type : ResearchTaskType.values()) {
            ResearchTaskHandler handler = registry.require(type);
            assertThat(handler.type()).isEqualTo(type);
            assertThat(registered.add(type)).isTrue();
        }
        assertThat(registered).containsExactlyInAnyOrder(ResearchTaskType.values());
    }
}
