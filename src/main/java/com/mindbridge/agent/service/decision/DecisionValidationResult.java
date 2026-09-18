package com.mindbridge.agent.service.decision;

import java.util.List;

/**
 * 决策草案校验结果。
 */
public record DecisionValidationResult(
        boolean valid,
        List<String> errors,
        List<ValidatedCitation> supporting,
        List<ValidatedCitation> opposing
) {
    public DecisionValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        supporting = supporting == null ? List.of() : List.copyOf(supporting);
        opposing = opposing == null ? List.of() : List.copyOf(opposing);
    }

    public static DecisionValidationResult invalid(List<String> errors) {
        return new DecisionValidationResult(false, errors, List.of(), List.of());
    }
}
