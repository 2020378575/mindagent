package com.mindbridge.agent.service.task;

/**
 * 瞬时依赖失败（模型、Chroma、解析依赖抖动）。允许在 max-attempts 内自动重试。
 */
public class TransientTaskException extends RuntimeException {

    public TransientTaskException(String message) {
        super(message);
    }

    public TransientTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
