package com.mindbridge.agent.config;

import com.mindbridge.agent.security.CurrentUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.ServerHttpSecurity;

@Configuration
@EnableWebFluxSecurity
/**
 * WebFlux 安全配置。
 *
 * <p>项目使用 HTTP Basic 简化演示登录；管理员接口要求 ADMIN 角色，
 * 普通 API 要求已登录用户。</p>
 */
public class SecurityConfig {

    private static final String ADMIN_ROLE = "ADMIN";

    @Bean
    public SecurityWebFilterChain securityFilterChain(
            ServerHttpSecurity http,
            ReactiveAuthenticationManager authenticationManager,
            @Value("${spring.ai.mcp.server.sse-endpoint:/sse}") String mcpSsePath,
            @Value("${spring.ai.mcp.server.sse-message-endpoint:/mcp/messages}") String mcpMessagePath
    ) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authenticationManager(authenticationManager)
                .httpBasic(Customizer.withDefaults())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.svg",
                                "/actuator/health").permitAll()
                        .pathMatchers(mcpSsePath, mcpMessagePath, mcpMessagePath + "/**").hasRole(ADMIN_ROLE)
                        .pathMatchers("/api/admin/**").hasRole(ADMIN_ROLE)
                        .pathMatchers("/api/reports/**").hasRole(ADMIN_ROLE)
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().authenticated())
                .build();
    }

    @Bean
    public ReactiveAuthenticationManager authenticationManager(
            CurrentUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
