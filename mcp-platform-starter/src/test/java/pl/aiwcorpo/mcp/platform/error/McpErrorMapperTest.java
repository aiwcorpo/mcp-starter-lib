package pl.aiwcorpo.mcp.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import pl.aiwcorpo.mcp.platform.ContractSchemas;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;
import pl.aiwcorpo.mcp.platform.security.TestCredentials;

/** Every error the mapper produces is a valid C10 object ({@code tool-error.schema.json}). */
class McpErrorMapperTest {

    private static final String CONNECT = "http://localhost:18090/connections?system={system}";

    private final McpErrorMapper mapper = new McpErrorMapper(List.of(), CONNECT);

    /** What Spring AI wraps every exception of a @Tool method in. */
    private static Throwable wrapped(Throwable cause) {
        return new RuntimeException("Error executing tool", cause);
    }

    private static String json(SafeError error) {
        String json = new SanitizedToolException(error).getMessage();
        assertThat(ContractSchemas.violations(ContractSchemas.TOOL_ERROR, json)).as(json).isEmpty();
        return json;
    }

    @Test
    void credentialRequiredNamesTheSystemAndThePortal() {
        SafeError error = mapper.map(wrapped(new CredentialRequiredException("jira")));

        assertThat(error.code()).isEqualTo("credential_required");
        assertThat(error.details()).containsEntry("system", "jira")
                .containsEntry("connectUrl", "http://localhost:18090/connections?system=jira");
        assertThat(json(error)).startsWith("{\"error\":\"credential_required\",\"message\":");
    }

    @Test
    void backendErrorsNeverReachTheModel() {
        SafeError error = mapper.map(wrapped(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                null, "{\"errors\":\"field x unknown at https://jira.internal\"}".getBytes(), null)));

        assertThat(error).isEqualTo(new SafeError("internal_error", "The tool could not complete the request."));
        json(error);
    }

    @Test
    void unreachableOrOverloadedBackendsAreUpstreamUnavailable() {
        assertThat(mapper.map(wrapped(new ResourceAccessException("I/O error on GET https://jira.internal"))).code())
                .isEqualTo("upstream_unavailable");
        assertThat(mapper.map(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "x", null, null, null))
                .code()).isEqualTo("upstream_unavailable");
        assertThat(mapper.map(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x", null, null, null))
                .code()).isEqualTo("internal_error");
    }

    @Test
    void theServersOwnValidationIsEchoedSoTheModelCanCorrectItself() {
        SafeError error = mapper.map(wrapped(new InvalidToolArgumentException("issueKey must look like ABC-123")));

        assertThat(error).isEqualTo(new SafeError("invalid_argument", "issueKey must look like ABC-123"));
        json(error);
    }

    @Test
    void aPlainIllegalArgumentExceptionGetsAFixedText() {
        SafeError error = mapper.map(wrapped(new IllegalArgumentException("Illegal character in path at index 9: "
                + "/rest/api/2/issue/{\"secret\":\"backend data\"}")));

        assertThat(error).isEqualTo(new SafeError("invalid_argument", "The arguments of the call are not valid."));
    }

    @Test
    void encodedFormsOfTheCredentialAreRemovedToo() {
        BackendCredential credential = new BackendCredential(BackendCredential.Scheme.BEARER, "glpat/a+b=c-0000",
                BackendCredential.Type.USER_SECRET);

        SafeError error = TestCredentials.with("jira", credential, () -> mapper.map(new InvalidToolArgumentException(
                "raw glpat/a+b=c-0000 url glpat%2Fa%2Bb%3Dc-0000 lower glpat%2fa%2bb%3dc-0000 json glpat\\/a+b=c-0000")));

        assertThat(error.message()).doesNotContain("glpat").contains("[REDACTED]");
    }

    @Test
    void anErrorMissingARequiredFieldBecomesInternalError() {
        ToolErrorMapping incomplete = ex -> Optional.of(new SafeError("confirmation_required", "Confirm it."));

        assertThat(new McpErrorMapper(List.of(incomplete), CONNECT).map(new RuntimeException()).code())
                .isEqualTo("internal_error");
        assertThat(new McpErrorMapper().map(new CredentialRequiredException("jira")).code())
                .as("credential_required needs connectUrl").isEqualTo("internal_error");
    }

    @Test
    void anEchoedMessageNeverCarriesTheCredential() {
        BackendCredential credential = new BackendCredential(BackendCredential.Scheme.BEARER, "jira-pat-0000secret",
                BackendCredential.Type.USER_SECRET);

        SafeError error = TestCredentials.with("jira", credential,
                () -> mapper.map(new InvalidToolArgumentException("token jira-pat-0000secret is not valid for ABC")));

        assertThat(error.message()).doesNotContain("jira-pat-0000secret").contains("[REDACTED]");
    }

    @Test
    void securityExceptionsStayOpaque() {
        SafeError error = mapper.map(new SecurityException("keystore /etc/mcp/tls/keystore.p12 unreadable"));

        assertThat(error).isEqualTo(new SafeError("access_denied", "Not authorized for this operation."));
    }

    @Test
    void aServerMappingWinsButMustUseAC10Code() {
        class NotVisible extends RuntimeException {
        }
        ToolErrorMapping good = ex -> ex instanceof NotVisible
                ? Optional.of(new SafeError("not_found_or_denied", "The issue does not exist or is not visible."))
                : Optional.empty();
        ToolErrorMapping bad = ex -> Optional.of(new SafeError("not_found", "nope", Map.of("stack", "x")));

        assertThat(new McpErrorMapper(List.of(good), CONNECT).map(wrapped(new NotVisible())).code())
                .isEqualTo("not_found_or_denied");
        assertThat(new McpErrorMapper(List.of(bad), CONNECT).map(new RuntimeException()).code())
                .isEqualTo("internal_error");
    }

    @Test
    void fieldsOfOtherCodesAreDroppedAndMessagesCut() {
        ToolErrorMapping chatty = ex -> Optional.of(new SafeError("access_denied", "y".repeat(5_000),
                Map.of("requiredEntitlements", List.of("mcp-jira-write"), "connectUrl", "https://elsewhere")));

        SafeError error = new McpErrorMapper(List.of(chatty), CONNECT).map(new RuntimeException());

        assertThat(error.message()).hasSize(500);
        assertThat(error.details()).containsOnlyKeys("requiredEntitlements");
        json(error);
    }

    @Test
    void anAlreadySanitizedErrorPassesUnchanged() {
        SafeError original = new SafeError("rate_limited", "Slow down.", Map.of("retryAfterSeconds", 5));

        assertThat(mapper.map(wrapped(new SanitizedToolException(original)))).isEqualTo(original);
    }

    @Test
    void theLogLineLeavesOutResponseBodiesAndTheCredential() {
        BackendCredential credential = new BackendCredential(BackendCredential.Scheme.BEARER, "jira-pat-0000secret",
                BackendCredential.Type.USER_SECRET);
        String described = TestCredentials.with("jira", credential, () -> McpErrorMapper.describeChain(wrapped(
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null,
                        "{\"echo\":\"Bearer jira-pat-0000secret\"}".getBytes(), null))));

        assertThat(described).contains("HTTP 401").doesNotContain("jira-pat-0000secret").doesNotContain("echo");
    }
}
