package pl.aiwcorpo.mcp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CredentialHeaderFilterTest {

    private static final String SECRET = "jira-pat-of-jkowalski-7Hq2";

    private record Seen(BackendCredential credential, List<String> headerNames, String authorization) {
    }

    private Seen run(CredentialHeaderFilter filter, MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                seen.set(new Seen(CredentialContext.current().orElse(null),
                        Collections.list(req.getHeaderNames()), req.getHeader("Authorization")));
            }
        }));
        assertThat(CredentialContext.current()).as("cleared after the request").isEmpty();
        return seen.get();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer internal.jwt.value");
        return request;
    }

    @Test
    void theGatewaysCredentialIsTakenAndHiddenFromTheRestOfTheRequest() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-MCP-Credential", "Bearer " + SECRET);
        request.addHeader("X-MCP-Credential-Type", "user-secret");

        Seen seen = run(new CredentialHeaderFilter("jira", null), request, new MockHttpServletResponse());

        assertThat(seen.credential().scheme()).isEqualTo(BackendCredential.Scheme.BEARER);
        assertThat(seen.credential().value()).isEqualTo(SECRET);
        assertThat(seen.credential().type()).isEqualTo(BackendCredential.Type.USER_SECRET);
        assertThat(seen.headerNames()).noneMatch(name -> name.equalsIgnoreCase("X-MCP-Credential")
                || name.equalsIgnoreCase("Authorization"));
        assertThat(seen.authorization()).isNull();
    }

    @Test
    void noCredentialMeansCredentialRequiredWhenAToolAsks() throws Exception {
        Seen seen = run(new CredentialHeaderFilter("jira", null), request(), new MockHttpServletResponse());

        assertThat(seen.credential()).isNull();
        assertThatThrownBy(() -> CredentialContext.require("jira")).isInstanceOf(CredentialRequiredException.class);
    }

    @Test
    void malformedOrAmbiguousCredentialsAreRefusedWithoutEchoingThem() throws Exception {
        String[][] cases = {
                {"Bearer " + SECRET, null},
                {null, "user-secret"},
                {"bearer " + SECRET, "user-secret"},
                {"Bearer", "user-secret"},
                {"Bearer " + SECRET + "\u0001", "user-secret"},
                {"Bearer " + SECRET, "user-password"},
        };
        for (String[] c : cases) {
            MockHttpServletRequest request = request();
            if (c[0] != null) {
                request.addHeader("X-MCP-Credential", c[0]);
            }
            if (c[1] != null) {
                request.addHeader("X-MCP-Credential-Type", c[1]);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            Seen seen = run(new CredentialHeaderFilter("jira", null), request, response);
            assertThat(seen).as(c[0] + " / " + c[1]).isNull();
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).doesNotContain(SECRET);
        }
    }

    @Test
    void patHeadersAreIgnoredOutsideDevPatMode() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-PAT-Jira", SECRET);

        Seen seen = run(new CredentialHeaderFilter("jira", null), request, new MockHttpServletResponse());

        assertThat(seen.credential()).isNull();
        assertThat(seen.headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-pat-"));
    }

    @Test
    void devPatModeTakesThePatOfTheServersSystemOnly() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-PAT-Jira", SECRET);
        request.addHeader("X-PAT-Gitlab", "gitlab-token-1234");

        Seen seen = run(new CredentialHeaderFilter("jira", "X-PAT-"), request, new MockHttpServletResponse());

        assertThat(seen.credential().value()).isEqualTo(SECRET);
        assertThat(seen.credential().type()).isEqualTo(BackendCredential.Type.USER_SECRET);
        assertThat(seen.headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-pat-"));
    }

    @Test
    void devPatModeRefusesTwoCredentials() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-PAT-Jira", SECRET);
        request.addHeader("X-MCP-Credential", "Bearer other-token-1234");
        request.addHeader("X-MCP-Credential-Type", "user-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(new CredentialHeaderFilter("jira", "X-PAT-"), request, response)).isNull();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void theCredentialNeverPrintsItsValue() {
        BackendCredential credential = new BackendCredential(BackendCredential.Scheme.BASIC,
                "dXNlcjpzM2NyZXQtcGFzcw==", BackendCredential.Type.SERVICE_ACCOUNT);

        assertThat(credential.toString()).doesNotContain("dXNlcjpzM2NyZXQtcGFzcw==").contains("Basic", "service-account");
        HttpHeaders headers = new HttpHeaders();
        credential.applyTo(headers);
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Basic dXNlcjpzM2NyZXQtcGFzcw==");
    }

    @Test
    void anotherSystemsCredentialIsAProgrammingError() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-MCP-Credential", "Bearer " + SECRET);
        request.addHeader("X-MCP-Credential-Type", "user-oauth");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        new CredentialHeaderFilter("jira", null).doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse res) {
                        try {
                            CredentialContext.require("gitlab");
                        } catch (RuntimeException ex) {
                            thrown.set(ex);
                        }
                    }
                }));
        assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.get().getMessage()).doesNotContain(SECRET);
    }
}
