package pl.aiwcorpo.mcp.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.security.MissingPatException;

class McpErrorMapperTest {

    /** A server-registered mapping, as mcp-gitlab does for GitLabApiException. */
    private static class NotFound extends RuntimeException {
        NotFound() {
            super("GitLab resource not found or not visible to this token (HTTP 404).");
        }
    }

    private final ToolErrorMapping mapping = ex -> ex instanceof NotFound
            ? Optional.of(new SafeError("not_found", ex.getMessage()))
            : Optional.empty();

    @Test
    void serverMappingWinsOverTheGenericDefault() {
        SafeError error = new McpErrorMapper(List.of(mapping)).map(new NotFound());

        assertThat(error.code()).isEqualTo("not_found");
        assertThat(error.message()).contains("HTTP 404");
    }

    @Test
    void withoutTheMappingTheSameExceptionIsOpaque() {
        SafeError error = new McpErrorMapper().map(new NotFound());

        assertThat(error).isEqualTo(new SafeError("internal_error", "The tool could not complete the request."));
    }

    @Test
    void unmatchedExceptionFallsThroughTheMappingToDefaults() {
        SafeError error = new McpErrorMapper(List.of(mapping)).map(new IllegalArgumentException("vmid must be positive"));

        assertThat(error).isEqualTo(new SafeError("invalid_argument", "vmid must be positive"));
    }

    @Test
    void missingPatNamesTheHeaderNotTheToken() {
        SafeError error = new McpErrorMapper().map(new MissingPatException("gitlab"));

        assertThat(error.code()).isEqualTo("missing_credential");
        assertThat(error.message()).isEqualTo("Missing PAT header X-PAT-Gitlab");
    }

    @Test
    void aServerMappingCannotFloodTheModel() {
        ToolErrorMapping chatty = ex -> Optional.of(new SafeError("verbose", "y".repeat(5_000)));

        SafeError error = new McpErrorMapper(List.of(chatty)).map(new RuntimeException());

        assertThat(error.message().length()).isLessThan(600);
    }

    // ---- the cause chain (1.0.2) ----------------------------------------------------------------

    /**
     * Stands in for Spring AI's {@code ToolExecutionException}, which is what every exception from a
     * {@code @Tool} method is wrapped in before this mapper ever sees it. Every assertion below goes
     * through it on purpose: a mapper that only inspects the thrown exception passes the naive tests
     * above and returns {@code internal_error} to every real caller.
     */
    private static Throwable wrapped(Throwable cause) {
        return new RuntimeException("Error executing tool", cause);
    }

    @Test
    void aForgottenHeaderIsNamedEvenWhenSpringAiWrappedIt() {
        SafeError error = new McpErrorMapper().map(wrapped(new MissingPatException("gitlab")));

        assertThat(error.code()).isEqualTo("missing_credential");
        assertThat(error.message()).isEqualTo("Missing PAT header X-PAT-Gitlab");
    }

    @Test
    void argumentValidationSurvivesTheWrapper() {
        SafeError error = new McpErrorMapper().map(wrapped(new IllegalArgumentException("vmid must be positive")));

        assertThat(error).isEqualTo(new SafeError("invalid_argument", "vmid must be positive"));
    }

    @Test
    void aServerMappingIsConsultedAtEveryLevelOfTheChain() {
        SafeError error = new McpErrorMapper(List.of(mapping)).map(wrapped(new NotFound()));

        assertThat(error.code()).isEqualTo("not_found");
    }

    /** A domain exception that is ALSO an IllegalArgumentException must keep the server's code. */
    @Test
    void aServerMappingWinsOverTheBuiltInAtTheSameLevel() {
        class DomainArgument extends IllegalArgumentException {
            DomainArgument() {
                super("no such queue");
            }
        }
        ToolErrorMapping domain = ex -> ex instanceof DomainArgument
                ? Optional.of(new SafeError("not_found", ex.getMessage()))
                : Optional.empty();

        SafeError error = new McpErrorMapper(List.of(domain)).map(wrapped(new DomainArgument()));

        assertThat(error.code()).isEqualTo("not_found");
    }

    /**
     * An inner decorator has already mapped this one. Re-mapping would bury a precise
     * {@code forbidden} inside a generic {@code internal_error} and lose both code and reason.
     */
    @Test
    void anAlreadySanitizedFailureIsPassedThroughUnchanged() {
        SafeError inner = new SafeError("forbidden", "The credentials are not granted that object.");

        SafeError error = new McpErrorMapper().map(wrapped(new SanitizedToolException(inner)));

        assertThat(error).isEqualTo(inner);
    }

    /** The message may itself contain a colon; rebuilding it by splitting would corrupt it. */
    @Test
    void aSanitizedMessageContainingAColonSurvives() {
        SafeError inner = new SafeError("timeout", "statement_timeout: aborted after 30s");

        SafeError error = new McpErrorMapper().map(wrapped(new SanitizedToolException(inner)));

        assertThat(error.message()).isEqualTo("statement_timeout: aborted after 30s");
    }

    @Test
    void anUnrecognisedFailureIsStillOpaqueHoweverDeeplyWrapped() {
        SafeError error = new McpErrorMapper().map(wrapped(wrapped(new IllegalStateException("pool exhausted"))));

        assertThat(error).isEqualTo(new SafeError("internal_error", "The tool could not complete the request."));
    }

    /** Two exceptions pointing at each other: the JVM forbids only DIRECT self-causation. */
    @Test
    void aCyclicCauseChainTerminates() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(new McpErrorMapper().map(first).code()).isEqualTo("internal_error");
    }
}
