package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FrontendSecurityRegressionTests {

    private static final String APP_JS_PATH = "static/app.js";
    private static final String CURRENT_SESSION_KEY = "evidencelab.workspace.v2";
    private static final String LEGACY_SESSION_KEY = "evidencelab.session.v1";

    @Test
    void basicCredentialsAreNeverPersistedInBrowserStorage() throws IOException {
        String source = readAppSource();

        assertThat(source)
                .doesNotContain("sessionStorage.setItem")
                .doesNotContain("localStorage.setItem");
    }

    @Test
    void startupRemovesLegacyAndCurrentCredentialStorageKeys() throws IOException {
        String source = readAppSource();

        assertThat(source)
                .contains("localStorage.removeItem(LEGACY_STORAGE_KEY)")
                .contains("sessionStorage.removeItem(LEGACY_STORAGE_KEY)")
                .contains("sessionStorage.removeItem(SESSION_KEY)");
        assertThat(source).contains(LEGACY_SESSION_KEY, CURRENT_SESSION_KEY);
    }

    private String readAppSource() throws IOException {
        ClassPathResource resource = new ClassPathResource(APP_JS_PATH);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
