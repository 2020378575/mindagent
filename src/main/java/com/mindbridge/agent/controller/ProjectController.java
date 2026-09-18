package com.mindbridge.agent.controller;

import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.dto.CreateResearchProjectRequest;
import com.mindbridge.agent.dto.ResearchProjectResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.project.ResearchProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(ProjectController.PROJECTS_PATH)
/**
 * 研究项目接口。只做绑定、入口权限和响应转换，不直接访问 Repository。
 */
public class ProjectController {

    static final String PROJECTS_PATH = "/api/projects";

    private final ResearchProjectService researchProjectService;

    public ProjectController(ResearchProjectService researchProjectService) {
        this.researchProjectService = researchProjectService;
    }

    @PostMapping
    public ResearchProjectResponse create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateResearchProjectRequest request
    ) {
        return ResearchProjectResponse.from(researchProjectService.create(currentUser.getId(), request));
    }

    @GetMapping
    public List<ResearchProjectResponse> list(@AuthenticationPrincipal CurrentUser currentUser) {
        return researchProjectService.list(currentUser.getId()).stream()
                .map(ResearchProjectResponse::from)
                .toList();
    }

    @GetMapping("/{projectId}")
    public ResearchProjectResponse get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId
    ) {
        return ResearchProjectResponse.from(owned(() ->
                researchProjectService.requireOwnedProject(currentUser.getId(), projectId)));
    }

    @PostMapping("/{projectId}/archive")
    public ResearchProjectResponse archive(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId
    ) {
        return ResearchProjectResponse.from(owned(() ->
                researchProjectService.archive(currentUser.getId(), projectId)));
    }

    private ResearchProject owned(Supplier<ResearchProject> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }
}
