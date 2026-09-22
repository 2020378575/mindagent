package com.mindbridge.agent.config;

import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.knowledge.KnowledgeIngestionService;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataInitializer implements ApplicationRunner {

    private static final String ROLE_USER = "ROLE_USER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String LEGACY_ADMIN_USERNAME = "admin";
    private static final String LEGACY_ADMIN_PASSWORD = "admin123";
    private static final String LEGACY_USER_USERNAME = "student";
    private static final String LEGACY_USER_PASSWORD = "student123";

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final MindBridgeProperties properties;

    public DataInitializer(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            KnowledgeIngestionService knowledgeIngestionService,
            MindBridgeProperties properties
    ) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 只有显式配置凭据才创建初始账号；内置知识库会按 source 补齐或刷新。
        retireLegacyAccount(LEGACY_ADMIN_USERNAME, LEGACY_ADMIN_PASSWORD);
        retireLegacyAccount(LEGACY_USER_USERNAME, LEGACY_USER_PASSWORD);
        seedUsers();
        knowledgeIngestionService.syncClasspathKnowledge();
    }

    private void seedUsers() {
        MindBridgeProperties.Bootstrap bootstrap = properties.getBootstrap();
        seedUser(bootstrap.getAdminUsername(), bootstrap.getAdminPassword(),
                "Research Admin", Set.of(ROLE_ADMIN, ROLE_USER));
        seedUser(bootstrap.getUserUsername(), bootstrap.getUserPassword(),
                "Research User", Set.of(ROLE_USER));
    }

    private void seedUser(String username, String password, String displayName, Set<String> roles) {
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (!hasUsername && !hasPassword) {
            return;
        }
        if (!hasUsername || !hasPassword) {
            throw new IllegalStateException("Bootstrap username and password must both be configured");
        }
        String normalizedUsername = username.trim();
        UserAccount existing = userAccountRepository.findByUsername(normalizedUsername).orElse(null);
        if (existing != null) {
            if (!existing.isEnabled() && isLegacyDemoPassword(existing)) {
                existing.setPassword(passwordEncoder.encode(password));
                existing.setEnabled(true);
                userAccountRepository.save(existing);
            }
            return;
        }
        UserAccount user = new UserAccount();
        user.setUsername(normalizedUsername);
        user.setDisplayName(displayName);
        user.setPassword(passwordEncoder.encode(password));
        user.setRoles(roles);
        userAccountRepository.save(user);
    }

    private void retireLegacyAccount(String username, String legacyPassword) {
        userAccountRepository.findByUsername(username).ifPresent(account -> {
            if (account.isEnabled() && passwordEncoder.matches(legacyPassword, account.getPassword())) {
                account.setEnabled(false);
                userAccountRepository.save(account);
            }
        });
    }

    private boolean isLegacyDemoPassword(UserAccount account) {
        return (LEGACY_ADMIN_USERNAME.equals(account.getUsername())
                && passwordEncoder.matches(LEGACY_ADMIN_PASSWORD, account.getPassword()))
                || (LEGACY_USER_USERNAME.equals(account.getUsername())
                && passwordEncoder.matches(LEGACY_USER_PASSWORD, account.getPassword()));
    }
}
