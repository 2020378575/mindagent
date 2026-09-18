package com.mindbridge.agent.controller;

import com.mindbridge.agent.domain.ResearchSource;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.dto.CreateSourceResponse;
import com.mindbridge.agent.dto.ResearchSourceResponse;
import com.mindbridge.agent.dto.ResearchTaskResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.document.ResearchSourceService;
import com.mindbridge.agent.service.task.ResearchTaskExecutor;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(ResearchSourceController.SOURCES_PATH)
/**
 * 项目资料接口。上传后创建 SOURCE_INGESTION 任务并立即返回 202。
 */
public class ResearchSourceController {

    static final String SOURCES_PATH = ProjectController.PROJECTS_PATH + "/{projectId}/sources";

    private final ResearchSourceService researchSourceService;
    private final ResearchTaskService researchTaskService;
    private final ResearchTaskExecutor researchTaskExecutor;

    public ResearchSourceController(
            ResearchSourceService researchSourceService,
            ResearchTaskService researchTaskService,
            ResearchTaskExecutor researchTaskExecutor
    ) {
        this.researchSourceService = researchSourceService;
        this.researchTaskService = researchTaskService;
        this.researchTaskExecutor = researchTaskExecutor;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<CreateSourceResponse>> upload(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @RequestPart("file") FilePart file
    ) {
        return DataBufferUtils.join(file.content())
                .map(buffer -> {
                    byte[] bytes = readBytes(buffer);
                    try {
                        ResearchSource source = researchSourceService.createPending(
                                currentUser.getId(),
                                projectId,
                                file.filename(),
                                contentType(file),
                                bytes
                        );
                        ResearchTask task = researchTaskService.create(
                                currentUser.getId(),
                                projectId,
                                new CreateResearchTaskRequest(
                                        "source-ingest-" + source.getId() + "-" + UUID.randomUUID(),
                                        ResearchTaskType.SOURCE_INGESTION,
                                        null,
                                        source.getId(),
                                        null,
                                        null
                                )
                        );
                        researchTaskExecutor.submit(task.getId());
                        return ResponseEntity.status(HttpStatus.ACCEPTED)
                                .body(new CreateSourceResponse(
                                        ResearchSourceResponse.from(source),
                                        ResearchTaskResponse.from(task)
                                ));
                    } catch (IllegalArgumentException exception) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
                    }
                });
    }

    @GetMapping
    public List<ResearchSourceResponse> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId
    ) {
        return owned(() -> researchSourceService.list(currentUser.getId(), projectId)).stream()
                .map(ResearchSourceResponse::from)
                .toList();
    }

    private String contentType(FilePart file) {
        MediaType mediaType = file.headers().getContentType();
        return mediaType == null ? null : mediaType.toString();
    }

    private byte[] readBytes(DataBuffer dataBuffer) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(dataBuffer.readableByteCount());
            dataBuffer.asInputStream().transferTo(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read uploaded file");
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }

    private List<ResearchSource> owned(Supplier<List<ResearchSource>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }
}
