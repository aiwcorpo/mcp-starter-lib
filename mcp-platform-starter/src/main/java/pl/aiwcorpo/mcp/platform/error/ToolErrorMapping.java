package pl.aiwcorpo.mcp.platform.error;

import java.util.Optional;

/**
 * Server-supplied mapping from a domain exception to a C10 error, consulted by {@link McpErrorMapper}
 * before its built-in rules.
 *
 * <pre>{@code
 * @Bean
 * ToolErrorMapping jiraErrors() {
 *     return ex -> ex instanceof IssueNotVisibleException
 *             ? Optional.of(new SafeError("not_found_or_denied", "The issue does not exist or is not visible."))
 *             : Optional.empty();
 * }
 * }</pre>
 *
 * <p><b>Contract:</b> the code must be a C10 code ({@link ToolErrorCode}), otherwise the client gets
 * {@code internal_error}. The message is handed verbatim to the AI client: author it yourself, never embed
 * an upstream response body, headers, a stack trace or a secret. Return {@link Optional#empty()} to fall
 * through to the next mapping and then the defaults.
 */
@FunctionalInterface
public interface ToolErrorMapping {

    Optional<McpErrorMapper.SafeError> map(Throwable ex);
}
