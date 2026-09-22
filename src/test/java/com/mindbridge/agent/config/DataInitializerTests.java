package com.mindbridge.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.knowledge.KnowledgeIngestionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class DataInitializerTests {

    private static final String ADMIN_USERNAME = "admin";
    private static final String LEGACY_PASSWORD = "admin123";
    private static final String NEW_PASSWORD = "new-secret";

    @Test
    void doesNotCreateAccountsWithoutExplicitCredentials() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        KnowledgeIngestionService knowledge = mock(KnowledgeIngestionService.class);

        new DataInitializer(users, encoder, knowledge, new MindBridgeProperties()).run(null);

        verify(users, never()).save(any(UserAccount.class));
    }

    @Test
    void disablesLegacyDemoAccountWhenNoReplacementIsConfigured() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        KnowledgeIngestionService knowledge = mock(KnowledgeIngestionService.class);
        UserAccount admin = legacyAdmin();
        when(users.findByUsername(ADMIN_USERNAME)).thenReturn(Optional.of(admin));
        when(encoder.matches(LEGACY_PASSWORD, admin.getPassword())).thenReturn(true);

        new DataInitializer(users, encoder, knowledge, new MindBridgeProperties()).run(null);

        assertThat(admin.isEnabled()).isFalse();
        verify(users).save(admin);
    }

    @Test
    void rotatesLegacyDemoAccountWhenReplacementIsConfigured() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        KnowledgeIngestionService knowledge = mock(KnowledgeIngestionService.class);
        UserAccount admin = legacyAdmin();
        when(users.findByUsername(ADMIN_USERNAME)).thenReturn(Optional.of(admin));
        when(encoder.matches(LEGACY_PASSWORD, admin.getPassword())).thenReturn(true);
        when(encoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getBootstrap().setAdminUsername(ADMIN_USERNAME);
        properties.getBootstrap().setAdminPassword(NEW_PASSWORD);

        new DataInitializer(users, encoder, knowledge, properties).run(null);

        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPassword()).isEqualTo("new-hash");
    }

    private UserAccount legacyAdmin() {
        UserAccount admin = new UserAccount();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPassword("legacy-hash");
        return admin;
    }
}
