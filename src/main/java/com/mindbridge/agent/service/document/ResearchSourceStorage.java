package com.mindbridge.agent.service.document;

/**
 * 研究资料原始文件存储。应用重启后仍需能按 storageKey 读回，供解析任务恢复。
 */
public interface ResearchSourceStorage {

    String FILE_NOT_FOUND_MESSAGE = "Research source file not found";

    StoredResearchSource store(Long ownerId, Long projectId, String filename, byte[] content);

    byte[] load(String storageKey);

    void delete(String storageKey);
}
