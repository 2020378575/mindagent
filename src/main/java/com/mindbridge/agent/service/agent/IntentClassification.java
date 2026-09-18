package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;

/**
 * 意图分类的结构化结果。
 */
public record IntentClassification(IntentType intent, double confidence) {
}
