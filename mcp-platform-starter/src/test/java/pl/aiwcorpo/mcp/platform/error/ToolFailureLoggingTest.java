package pl.aiwcorpo.mcp.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/** The level of {@code event=tool_failed} follows what the failure means; expected outcomes are not errors. */
class ToolFailureLoggingTest {

    static class IssueGone extends RuntimeException {
        IssueGone() {
            super("issue ABC-123 does not exist, secret-marker-31");
        }
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(McpErrorMapper.class);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final McpErrorMapper mapper = new McpErrorMapper(List.of(ex -> ex instanceof IssueGone
            ? Optional.of(new McpErrorMapper.SafeError("not_found_or_denied", "Not found.")) : Optional.empty()), null);

    ToolFailureLoggingTest() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(lines);
    }

    private ILoggingEvent only() {
        assertThat(lines.list).hasSize(1);
        return lines.list.getFirst();
    }

    @Test
    void aDomainExceptionIsInfoWithTheCodeAndTheClassButNotTheMessage() {
        mapper.map(new RuntimeException("Error executing tool", new IssueGone()));

        ILoggingEvent event = only();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("event=tool_failed", "code=not_found_or_denied",
                IssueGone.class.getName()).doesNotContain("secret-marker-31").doesNotContain("ABC-123");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void theCallersMistakesAndMissingCredentialsAreInfo() {
        mapper.map(new InvalidToolArgumentException("limit is above the maximum 100"));
        new McpErrorMapper(List.of(), "http://localhost:18090/connections?system={system}")
                .map(new CredentialRequiredException("jira"));
        mapper.map(new SecurityException("no"));

        assertThat(lines.list).hasSize(3).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    void anUnavailableBackendIsWarn() {
        mapper.map(new ResourceAccessException("I/O error"));

        assertThat(only().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void onlyWhatNobodyAnticipatedIsError() {
        mapper.map(new IllegalStateException("boom"));

        ILoggingEvent event = only();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("code=internal_error", "boom", "at=");
    }
}
