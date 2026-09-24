package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.ResearchWorkspaceResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.workspace.ResearchWorkspaceService;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(ResearchWorkspaceController.PATH)
/**
 * 决策优先工作区聚合接口。
 */
public class ResearchWorkspaceController {

    static final String PATH = ProjectController.PROJECTS_PATH + "/{projectId}/workspace";

    private final ResearchWorkspaceService researchWorkspaceService;

    public ResearchWorkspaceController(ResearchWorkspaceService researchWorkspaceService) {
        this.researchWorkspaceService = researchWorkspaceService;
    }

    @GetMapping
    public Mono<ResearchWorkspaceResponse> load(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId
    ) {
        return BlockingRequests.supply(() -> owned(() ->
                researchWorkspaceService.load(currentUser.getId(), projectId)));
    }

    private ResearchWorkspaceResponse owned(Supplier<ResearchWorkspaceResponse> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }
}
