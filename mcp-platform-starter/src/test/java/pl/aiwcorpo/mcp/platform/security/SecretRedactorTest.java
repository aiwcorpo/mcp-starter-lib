package pl.aiwcorpo.mcp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    @AfterEach
    void clear() {
        CredentialContext.clear();
    }

    @Test
    void theValueIsRemoved() {
        CredentialContext.bind("jira", new BackendCredential(BackendCredential.Scheme.BEARER, "pat-abc-123456",
                BackendCredential.Type.USER_SECRET));

        assertThat(SecretRedactor.redact("401: token pat-abc-123456 rejected"))
                .isEqualTo("401: token [REDACTED] rejected");
    }

    @Test
    void basicIsRemovedEncodedAndDecoded() {
        String encoded = Base64.getEncoder().encodeToString("svc-jira:Wint3r-2026".getBytes(StandardCharsets.UTF_8));
        CredentialContext.bind("jira", new BackendCredential(BackendCredential.Scheme.BASIC, encoded,
                BackendCredential.Type.SERVICE_ACCOUNT));

        assertThat(SecretRedactor.redact("sent " + encoded + " for svc-jira:Wint3r-2026, password Wint3r-2026"))
                .doesNotContain(encoded).doesNotContain("Wint3r-2026");
    }

    @Test
    void withoutACredentialNothingChanges() {
        assertThat(SecretRedactor.redact("plain text")).isEqualTo("plain text");
    }
}
