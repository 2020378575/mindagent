package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.ConfirmDecisionRequest;
import com.mindbridge.agent.dto.DecisionResponse;
import com.mindbridge.agent.dto.DecisionReviewResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.decision.DecisionService;
import com.mindbridge.agent.service.decision.DecisionValidationException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(DecisionController.PATH)
public class DecisionController {

    static final String PATH = "/api/projects/{projectId}/decisions";

    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @GetMapping
    public List<DecisionResponse> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId
    ) {
        return owned(() -> decisionService.list(currentUser.getId(), projectId)).stream()
                .map(DecisionResponse::from)
                .toList();
    }

    @PostMapping("/from-task/{taskId}")
    public DecisionResponse createDraftFromTask(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable Long taskId
    ) {
        return DecisionResponse.from(owned(() ->
                decisionService.createDraftFromTask(currentUser.getId(), projectId, taskId)));
    }

    @PostMapping("/{decisionId}/confirm")
    public DecisionResponse confirm(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable Long decisionId,
            @RequestBody(required = false) @Valid ConfirmDecisionRequest request
    ) {
        return DecisionResponse.from(owned(() ->
                decisionService.confirm(currentUser.getId(), projectId, decisionId)));
    }

    @PostMapping("/{decisionId}/regenerate")
    public DecisionResponse regenerate(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable Long decisionId,
            @RequestParam Long taskId
    ) {
        return DecisionResponse.from(owned(() ->
                decisionService.regenerate(currentUser.getId(), projectId, decisionId, taskId)));
    }

    @PostMapping("/{decisionId}/start-validation")
    public DecisionResponse startValidation(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable Long decisionId,
            @RequestParam Long experimentId
    ) {
        return DecisionResponse.from(owned(() ->
                decisionService.startValidation(currentUser.getId(), projectId, decisionId, experimentId)));
    }

    @PostMapping("/{decisionId}/reviews/{reviewTaskId}")
    public DecisionReviewResponse confirmReview(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long projectId,
            @PathVariable Long decisionId,
            @PathVariable Long reviewTaskId
    ) {
        return DecisionReviewResponse.from(owned(() ->
                decisionService.confirmReview(currentUser.getId(), projectId, decisionId, reviewTaskId)));
    }

    private <T> T owned(Supplier<T> action) {
        try {
            return action.get();
        } catch (DecisionValidationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }
}
