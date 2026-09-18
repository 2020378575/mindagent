package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.CompleteExperimentRequest;
import com.mindbridge.agent.dto.CreateExperimentRequest;
import com.mindbridge.agent.dto.ExperimentResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.experiment.ExperimentService;
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
@RequestMapping(ExperimentController.PATH)
public class ExperimentController {

    static final String PATH = "/api/projects/{projectId}/experiments";

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @GetMapping
    public List<ExperimentResponse> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId
    ) {
        return owned(() -> experimentService.list(currentUser.getId(), projectId)).stream()
                .map(ExperimentResponse::from)
                .toList();
    }

    @PostMapping
    public ExperimentResponse create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @Valid @RequestBody CreateExperimentRequest request
    ) {
        return ExperimentResponse.from(owned(() ->
                experimentService.create(currentUser.getId(), projectId, request)));
    }

    @PostMapping("/{experimentId}/complete")
    public ExperimentResponse complete(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable Long experimentId,
            @Valid @RequestBody CompleteExperimentRequest request
    ) {
        return ExperimentResponse.from(owned(() ->
                experimentService.complete(currentUser.getId(), projectId, experimentId, request)));
    }

    private <T> T owned(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }
}
