package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.ConversationResponse;
import com.mindbridge.agent.dto.AlertRecordResponse;
import com.mindbridge.agent.dto.AgentRunTraceResponse;
import com.mindbridge.agent.dto.AgentRunTraceSummaryResponse;
import com.mindbridge.agent.dto.ExcelRecordResponse;
import com.mindbridge.agent.dto.ReportResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.AgentRunTraceService;
import com.mindbridge.agent.service.ReportService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
/**
 * 报告、Excel 记录、邮件记录和完整会话查询接口。
 *
 * <p>管理员后台的数据列表和详情弹窗主要由这些接口驱动。</p>
 */
public class ReportController {

    private final ReportService reportService;
    private final AgentRunTraceService agentRunTraceService;

    public ReportController(ReportService reportService, AgentRunTraceService agentRunTraceService) {
        this.reportService = reportService;
        this.agentRunTraceService = agentRunTraceService;
    }

    @GetMapping("/reports/me")
    public Mono<List<ReportResponse>> myReports(@AuthenticationPrincipal CurrentUser currentUser) {
        return BlockingRequests.supply(() -> reportService.myReports(currentUser.getId()).stream()
                .map(ReportResponse::from)
                .toList());
    }

    @GetMapping("/admin/reports")
    public Mono<List<ReportResponse>> latestReports() {
        // 管理员统计大屏使用这个接口作为对话报告主数据源。
        return BlockingRequests.supply(() -> reportService.latestReports().stream()
                .map(ReportResponse::from)
                .toList());
    }

    @GetMapping("/admin/excel-records")
    public Mono<List<ExcelRecordResponse>> excelRecords() {
        return BlockingRequests.supply(reportService::excelRecords);
    }

    @GetMapping("/admin/alerts")
    public Mono<List<AlertRecordResponse>> alertRecords() {
        return BlockingRequests.supply(reportService::alertRecords);
    }

    @GetMapping("/admin/conversations/{sessionId}")
    public Mono<ConversationResponse> conversation(@PathVariable String sessionId) {
        // 点开任一后台记录时读取完整会话，便于管理员回看上下文。
        return BlockingRequests.supply(() -> reportService.conversation(sessionId));
    }

    @GetMapping("/admin/conversations/{sessionId}/run-traces")
    public Mono<List<AgentRunTraceResponse>> conversationRunTraces(@PathVariable String sessionId) {
        return BlockingRequests.supply(() -> agentRunTraceService.tracesForSession(sessionId));
    }

    @GetMapping("/admin/run-traces")
    public Mono<List<AgentRunTraceSummaryResponse>> latestRunTraces() {
        return BlockingRequests.supply(agentRunTraceService::latestTraces);
    }

    @GetMapping("/admin/run-traces/{traceId}")
    public Mono<AgentRunTraceResponse> runTrace(@PathVariable String traceId) {
        return BlockingRequests.supply(() -> agentRunTraceService.trace(traceId));
    }
}
