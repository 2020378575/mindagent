package com.mindbridge.agent.service.document;

/**
 * 原始资料落盘后的不透明存储结果。文件名不得进入文件系统路径。
 */
public record StoredResearchSource(
        String storageKey,
        String sha256,
        long sizeBytes
) {
}
