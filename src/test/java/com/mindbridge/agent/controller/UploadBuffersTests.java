package com.mindbridge.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.service.document.ResearchSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class UploadBuffersTests {

    @Test
    void acceptsFileAtLimit() {
        FilePart file = fileWithSize(ResearchSourceService.MAX_FILE_BYTES);
        StepVerifier.create(UploadBuffers.read(file))
                .assertNext(bytes -> assertThat(bytes).hasSize(ResearchSourceService.MAX_FILE_BYTES))
                .verifyComplete();
    }

    @Test
    void rejectsFileBeforeMaterializingBeyondLimit() {
        FilePart file = fileWithSize(ResearchSourceService.MAX_FILE_BYTES + 1);
        StepVerifier.create(UploadBuffers.read(file))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                })
                .verify();
    }

    private FilePart fileWithSize(int bytes) {
        FilePart file = mock(FilePart.class);
        when(file.content()).thenReturn(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[bytes])));
        return file;
    }
}
