package com.mindbridge.agent.service.decision;

/**
 * 决策引用校验失败。
 */
public class DecisionValidationException extends IllegalArgumentException {

    public DecisionValidationException(String message) {
        super(message);
    }
}
