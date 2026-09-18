package com.mindbridge.agent.service.agent;

/**
 * 证据批判中的单条主张。
 */
public record EvidenceClaim(
        String claim,
        Long chunkId,
        String stance,
        String note
) {
}
