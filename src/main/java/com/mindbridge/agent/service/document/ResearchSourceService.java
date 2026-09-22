package com.mindbridge.agent.service.document;

import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchSource;
import com.mindbridge.agent.domain.SourceStatus;
import com.mindbridge.agent.domain.SourceType;
import com.mindbridge.agent.repository.ResearchSourceRepository;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 研究资料生命周期。解析前后分别落状态，整段流程不加一个大事务，失败后仍能读到 FAILED。
 */
public class ResearchSourceService {

    public static final String SOURCE_NOT_FOUND_MESSAGE = "Research source not found";
    static final String EMPTY_FILE_MESSAGE = "Research source file is empty";
    public static final String FILE_TOO_LARGE_MESSAGE = "Research source file exceeds 10MB";
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    static final int MAX_FAILURE_MESSAGE_LENGTH = 500;

    private final ResearchProjectService researchProjectService;
    private final ResearchSourceRepository researchSourceRepository;
    private final ResearchSourceStorage researchSourceStorage;
    private final DocumentParsingService documentParsingService;

    public ResearchSourceService(
            ResearchProjectService researchProjectService,
            ResearchSourceRepository researchSourceRepository,
            ResearchSourceStorage researchSourceStorage,
            DocumentParsingService documentParsingService
    ) {
        this.researchProjectService = researchProjectService;
        this.researchSourceRepository = researchSourceRepository;
        this.researchSourceStorage = researchSourceStorage;
        this.documentParsingService = documentParsingService;
    }

    @Transactional
    public ResearchSource createPending(
            Long userId,
            Long projectId,
            String filename,
            String contentType,
            byte[] content
    ) {
        ResearchProject project = researchProjectService.requireOwnedProject(userId, projectId);
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException(EMPTY_FILE_MESSAGE);
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(FILE_TOO_LARGE_MESSAGE);
        }
        SourceType sourceType = documentParsingService.resolveSourceType(filename, contentType);
        StoredResearchSource stored = researchSourceStorage.store(userId, projectId, filename, content);
        ResearchSource source = new ResearchSource();
        source.setProject(project);
        source.setOwner(project.getOwner());
        source.setFilename(DocumentNames.displayFilename(filename));
        source.setContentType(contentType);
        source.setSourceType(sourceType);
        source.setStatus(SourceStatus.PENDING);
        source.setStorageKey(stored.storageKey());
        source.setSha256(stored.sha256());
        source.setSizeBytes(stored.sizeBytes());
        return researchSourceRepository.save(source);
    }

    public ParsedDocument parse(Long userId, Long projectId, Long sourceId) {
        ResearchSource source = requireOwnedSource(userId, projectId, sourceId);
        markParsing(source.getId());
        try {
            byte[] bytes = researchSourceStorage.load(source.getStorageKey());
            ParsedDocument parsed = documentParsingService.parse(
                    source.getFilename(),
                    source.getContentType(),
                    bytes
            );
            markIndexing(source.getId(), parsed.sections().size());
            return parsed;
        } catch (RuntimeException exception) {
            String safeMessage = safeFailureMessage(exception);
            markFailed(source.getId(), safeMessage);
            if (exception instanceof DocumentParseException parseException) {
                throw parseException;
            }
            throw new DocumentParseException(safeMessage, exception);
        }
    }

    @Transactional
    public ResearchSource markParsing(Long sourceId) {
        ResearchSource source = requiredSource(sourceId);
        source.setStatus(SourceStatus.PARSING);
        source.setFailureMessage(null);
        return researchSourceRepository.save(source);
    }

    @Transactional
    public ResearchSource markIndexing(Long sourceId, int sectionCount) {
        ResearchSource source = requiredSource(sourceId);
        source.setStatus(SourceStatus.INDEXING);
        source.setSectionCount(sectionCount);
        source.setFailureMessage(null);
        return researchSourceRepository.save(source);
    }

    @Transactional
    public ResearchSource markReady(Long sourceId, int sectionCount, int chunkCount) {
        ResearchSource source = requiredSource(sourceId);
        source.setStatus(SourceStatus.READY);
        source.setSectionCount(sectionCount);
        source.setChunkCount(chunkCount);
        source.setFailureMessage(null);
        return researchSourceRepository.save(source);
    }

    @Transactional
    public ResearchSource markFailed(Long sourceId, String safeFailureMessage) {
        ResearchSource source = requiredSource(sourceId);
        source.setStatus(SourceStatus.FAILED);
        source.setFailureMessage(safeFailureMessage);
        return researchSourceRepository.save(source);
    }

    @Transactional(readOnly = true)
    public ResearchSource requireOwnedSource(Long userId, Long projectId, Long sourceId) {
        researchProjectService.requireOwnedProject(userId, projectId);
        return researchSourceRepository.findByIdAndProject_IdAndOwner_Id(sourceId, projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException(SOURCE_NOT_FOUND_MESSAGE));
    }

    @Transactional(readOnly = true)
    public List<ResearchSource> list(Long userId, Long projectId) {
        researchProjectService.requireOwnedProject(userId, projectId);
        return researchSourceRepository.findByProject_IdAndOwner_IdOrderByCreatedAtDesc(projectId, userId);
    }

    private ResearchSource requiredSource(Long sourceId) {
        return researchSourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException(SOURCE_NOT_FOUND_MESSAGE));
    }

    private String safeFailureMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        message = message.replaceAll("(/[^\\s:]+)+", "[path]");
        if (message.length() > MAX_FAILURE_MESSAGE_LENGTH) {
            return message.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
        }
        return message;
    }
}
