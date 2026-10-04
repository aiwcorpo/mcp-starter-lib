package pl.aiwcorpo.mcp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PatForwardingFilterTest {

    @Test
    void extractsPatHeadersIntoContextThenClears() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-PAT-Jira", "pat-jira-123");
        request.addHeader("X-PAT-Confluence", "pat-conf-456");
        request.addHeader("X-Other", "ignored");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] seenDuringRequest = {false};
        FilterChain chain = (req, res) ->
                seenDuringRequest[0] =
                        CredentialContext.pat("jira").orElse("").equals("pat-jira-123")
                        && CredentialContext.pat("confluence").orElse("").equals("pat-conf-456")
                        && CredentialContext.pat("other").isEmpty();

        new PatForwardingFilter("X-PAT-").doFilter(request, response, chain);

        assertThat(seenDuringRequest[0]).isTrue();            // PATs available while handling the request
        assertThat(CredentialContext.pat("jira")).isEmpty();  // and cleared once it completes
    }
}
