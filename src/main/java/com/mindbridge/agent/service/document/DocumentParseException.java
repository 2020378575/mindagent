package com.mindbridge.agent.service.document;

/**
 * 研究资料解析失败。调用方必须先把来源标为 FAILED，再抛出该异常。
 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
