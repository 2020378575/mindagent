package com.mindbridge.agent.service.project;

import com.mindbridge.agent.domain.ProjectStatus;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.CreateResearchProjectRequest;
import com.mindbridge.agent.repository.ResearchProjectRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 研究项目边界服务。后续所有项目数据访问必须先经过 {@link #requireOwnedProject}。
 */
public class ResearchProjectService {

    static final String PROJECT_NOT_FOUND_MESSAGE = "Research project not found";

    private final ResearchProjectRepository researchProjectRepository;
    private final UserAccountRepository userAccountRepository;

    public ResearchProjectService(
            ResearchProjectRepository researchProjectRepository,
            UserAccountRepository userAccountRepository
    ) {
        this.researchProjectRepository = researchProjectRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public ResearchProject create(Long userId, CreateResearchProjectRequest request) {
        UserAccount owner = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ResearchProject project = new ResearchProject();
        project.setOwner(owner);
        project.setName(request.name().trim());
        project.setObjective(request.objective().trim());
        project.setConstraints(blankToNull(request.constraints()));
        project.setStatus(ProjectStatus.ACTIVE);
        return researchProjectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public List<ResearchProject> list(Long userId) {
        return researchProjectRepository.findByOwner_IdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public ResearchProject requireOwnedProject(Long userId, Long projectId) {
        return researchProjectRepository.findByIdAndOwner_Id(projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException(PROJECT_NOT_FOUND_MESSAGE));
    }

    @Transactional
    public ResearchProject archive(Long userId, Long projectId) {
        ResearchProject project = requireOwnedProject(userId, projectId);
        project.setStatus(ProjectStatus.ARCHIVED);
        return researchProjectRepository.save(project);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
