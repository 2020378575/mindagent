package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.KnowledgeIngestRequest;
import com.mindbridge.agent.dto.KnowledgeIngestResponse;
import com.mindbridge.agent.service.knowledge.KnowledgeFileService;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/knowledge")
/**
 * 管理员知识库维护接口。
 *
 * <p>支持直接写入文本，也支持上传 PDF、Markdown、txt 文件作为 RAG 知识库来源。</p>
 */
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileService knowledgeFileService;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeFileService knowledgeFileService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeFileService = knowledgeFileService;
    }

    @PostMapping
    public Mono<KnowledgeIngestResponse> ingest(@Valid @RequestBody KnowledgeIngestRequest request) {
        // JSON 接口适合脚本或调试时直接写入一段知识。
        return BlockingRequests.supply(() -> {
            int chunks = knowledgeService.ingest(request.source(), request.content());
            return new KnowledgeIngestResponse(request.source(), chunks);
        });
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<KnowledgeIngestResponse> ingestFile(@RequestPart(UploadBuffers.FILE_PART_NAME) FilePart file) {
        // WebFlux 的 FilePart 是流式数据，这里合并成 byte[] 后交给文件解析服务。
        return UploadBuffers.read(file)
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    int chunks = knowledgeFileService.ingest(file.filename(), bytes);
                    return new KnowledgeIngestResponse(file.filename(), chunks);
                });
    }
}
