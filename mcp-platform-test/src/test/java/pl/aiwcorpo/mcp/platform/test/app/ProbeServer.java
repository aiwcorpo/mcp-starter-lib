package pl.aiwcorpo.mcp.platform.test.app;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

/** A server with probe tools, run over the real transport by the tests of this module. */
@SpringBootApplication
public class ProbeServer {

    @Bean
    ProbeTools probeTools(@Value("${probe.backend-url:http://127.0.0.1:1}") String backendUrl) {
        return new ProbeTools(RestClient.builder().baseUrl(backendUrl).build());
    }

    @Bean
    ToolCallbackProvider tools(ProbeTools probe) {
        return MethodToolCallbackProvider.builder().toolObjects(probe).build();
    }

    @Bean
    ToolCallbackProvider limitedTools() {
        return SchemaLimitedToolCallbacks.of(new LimitedTools());
    }

    /** A tool with limits in its schema, registered the way servers register theirs. */
    public static class LimitedTools {

        @Tool(name = "lookup_issue", description = "Looks an issue up by key.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String lookupIssue(
                @ToolParam(description = "Issue key, e.g. ABC-1")
                @ArgumentLimits(maxLength = 16, pattern = "^[A-Z]+-[0-9]+$") String issueKey,
                @ToolParam(description = "Page size", required = false)
                @ArgumentLimits(minimum = 1, maximum = 100) Integer limit) {
            return "found " + issueKey;
        }
    }

    public static class ProbeTools {

        private final RestClient backend;

        ProbeTools(RestClient backend) {
            this.backend = backend;
        }

        @Tool(name = "probe_credential", description = "Returns the credential this call can see.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String probeCredential() {
            BackendCredential credential = CredentialContext.require("jira");
            return credential.type().wireName() + " " + credential.authorizationValue();
        }

        @Tool(name = "probe_caller", description = "Returns the verified caller.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String probeCaller() {
            CallerContext caller = CallerContext.current().orElseThrow();
            return caller.username() + "|" + caller.tool() + "|" + caller.requestId();
        }

        @Tool(name = "get_issue", description = "Reads an issue from the backend with the caller's credential.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String getIssue(String issueKey) {
            return backend.get().uri("/rest/api/2/issue/{key}", issueKey)
                    .headers(CredentialContext.require("jira")::applyTo)
                    .retrieve().body(String.class);
        }

        @Tool(name = "get_issue_fields", description = "Reads an issue and parses it into fields.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String getIssueFields(String issueKey) {
            java.util.Map<?, ?> fields = backend.get().uri("/rest/api/2/issue/{key}", issueKey)
                    .headers(CredentialContext.require("jira")::applyTo)
                    .retrieve().body(java.util.Map.class);
            return String.valueOf(fields == null ? 0 : fields.size());
        }

        @Tool(name = "leaky_tool", description = "Puts the credential into an exception message, as careless code would.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String leakyTool() {
            throw new InvalidToolArgumentException("token " + CredentialContext.require("jira").value() + " rejected");
        }
    }
}
