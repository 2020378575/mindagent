package com.mindbridge.agent.service.mcp;

import com.mindbridge.agent.domain.OpsArchiveRecord;

/**
 * 归档记录写入 Excel 的工具接口。
 *
 * <p>本地文件写入和远程 MCP 写入都实现这个接口。</p>
 */
public interface ExcelReportWriter {

    void write(OpsArchiveRecord report);
}
