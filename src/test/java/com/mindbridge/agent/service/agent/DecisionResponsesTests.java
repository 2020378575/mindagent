package com.mindbridge.agent.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DecisionResponsesTests {

    @Test
    void readsRecommendationFromFencedJson() {
        String raw = """
                ```json
                {"recommendation":"12GB 应选 QLoRA","rationale":"显存更低",}
                ```
                """;

        assertThat(DecisionResponses.recommendation(raw)).isEqualTo("12GB 应选 QLoRA");
    }

    @Test
    void readsRecommendationWhenJsonIsTruncated() {
        String raw = "{\"options\":[],\"recommendation\":\"选 QLoRA 并把基座量化到 4-bit\",\"rationale\":\"截断";

        assertThat(DecisionResponses.recommendation(raw)).isEqualTo("选 QLoRA 并把基座量化到 4-bit");
    }
}
