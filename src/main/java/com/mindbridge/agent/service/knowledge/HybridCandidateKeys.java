package com.mindbridge.agent.service.knowledge;

/** 两条混合检索路径共用的候选去重键。 */
final class HybridCandidateKeys {

    private static final String ID_PREFIX = "id:";
    private static final String CONTENT_PREFIX = "content:";
    private static final String SEPARATOR = ":";

    private HybridCandidateKeys() {
    }

    static String forResult(SearchResult result) {
        if (result.chunkId() != null) {
            return ID_PREFIX + result.chunkId();
        }
        return CONTENT_PREFIX + result.source() + SEPARATOR + result.content();
    }
}
