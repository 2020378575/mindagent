package com.mindbridge.agent.service.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ResearchProjectRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchProjectServiceTests {

    @Mock
    private ResearchProjectRepository repository;

    @Mock
    private UserAccountRepository userAccountRepository;

    private ResearchProjectService service;

    @BeforeEach
    void setUp() {
        service = new ResearchProjectService(repository, userAccountRepository);
    }

    @Test
    void returnsProjectOwnedByCurrentUser() {
        ResearchProject project = project(10L, user(7L));
        when(repository.findByIdAndOwner_Id(10L, 7L)).thenReturn(Optional.of(project));

        assertThat(service.requireOwnedProject(7L, 10L)).isSameAs(project);
    }

    @Test
    void hidesProjectOwnedByAnotherUser() {
        when(repository.findByIdAndOwner_Id(10L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwnedProject(7L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Research project not found");
    }

    private ResearchProject project(Long id, UserAccount owner) {
        ResearchProject project = new ResearchProject();
        project.setId(id);
        project.setOwner(owner);
        project.setName("LoRA vs QLoRA");
        project.setObjective("Choose an adapter method under 12GB VRAM.");
        return project;
    }

    private UserAccount user(Long id) {
        UserAccount user = new UserAccount();
        user.setUsername("user-" + id);
        user.setDisplayName("User " + id);
        user.setPassword("secret");
        return user;
    }
}
