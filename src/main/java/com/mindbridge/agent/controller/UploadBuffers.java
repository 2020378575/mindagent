package com.mindbridge.agent.controller;

import com.mindbridge.agent.service.document.ResearchSourceService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** 在合并上传内容时执行大小限制，避免超限文件先进入堆内存。 */
final class UploadBuffers {

    static final String FILE_PART_NAME = "file";

    private UploadBuffers() {
    }

    static Mono<byte[]> read(FilePart file) {
        return DataBufferUtils.join(file.content(), ResearchSourceService.MAX_FILE_BYTES)
                .map(UploadBuffers::copyAndRelease)
                .onErrorMap(DataBufferLimitException.class,
                        exception -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                ResearchSourceService.FILE_TOO_LARGE_MESSAGE));
    }

    private static byte[] copyAndRelease(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
