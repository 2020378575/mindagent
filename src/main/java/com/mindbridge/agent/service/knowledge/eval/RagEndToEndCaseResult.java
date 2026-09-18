package com.mindbridge.agent.service.knowledge.eval;

import java.util.List;

public record RagEndToEndCaseResult(
        String id,
        String question,
        String expectedIntent,
        String actualIntent,
        List<String> retrievedSources,
        List<String> retrievedContexts,
        String answer,
        boolean passed,
        List<String> failures
) {
}
