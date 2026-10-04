package pl.aiwcorpo.mcp.platform.error;

import java.util.Optional;

/**
 * Server-supplied mapping from a domain exception to a safe MCP error, consulted by
 * {@link McpErrorMapper} before its built-in rules.
 *
 * <p>Exists so a server can surface useful, self-authored failure messages ("resource not found",
 * "rate limit reached") without either (a) flattening them into the generic {@code internal_error},
 * or (b) making its {@code mcp-core} domain depend on this platform. Register the bean in the
 * server's bootstrap module, which already depends on the starter:
 *
 * <pre>{@code
 * @Bean
 * ToolErrorMapping gitLabErrors() {
 *     return ex -> ex instanceof GitLabApiException e
 *             ? Optional.of(new SafeError(codeFor(e.status()), e.getMessage()))
 *             : Optional.empty();
 * }
 * }</pre>
 *
 * <p><b>Contract:</b> a message returned here is handed verbatim to the AI client. It must be authored
 * by you and must never embed an upstream response body, headers, a stack trace or a secret.
 * Return {@link Optional#empty()} to fall through to the next mapping and then the defaults.
 */
@FunctionalInterface
public interface ToolErrorMapping {

    Optional<McpErrorMapper.SafeError> map(Throwable ex);
}
