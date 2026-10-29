package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolAction;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.resource.ResourceBinding;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * CC-146 for a type whose container the identifier does not name (a Service Desk ticket): a caller with no queue in the bundle gets
 * {@code not_found_or_denied} before the resolver is asked, and a ticket outside the queues of a caller never turns into another answer
 * than a ticket that does not exist, whatever the system reports about it.
 */
class ScopeGrantTest {

    private static final String DENIED = "{\"error\":\"not_found_or_denied\",\"message\":\"The resource does not exist or you do not have access to it.\"}";

    private static TestGateway gateway;
    private static PolicyEngine engine;
    private final List<String> asked = new ArrayList<>();
    private Map<String, ResolvedResource> tickets = Map.of();

    @BeforeAll
    static void start() throws Exception {
        gateway = TestGateway.start("mcp-servicedesk");
        McpPlatformProperties props = new McpPlatformProperties();
        Map<String, String> properties = gateway.serverProperties();
        McpPlatformProperties.Policy policy = props.getPolicy();
        policy.getMain().setUrl(properties.get("aiwcorpo.mcp.platform.policy.main.url"));
        policy.getMain().setKeyId("test-policy-bundle");
        policy.getMain().setPublicKey(properties.get("aiwcorpo.mcp.platform.policy.main.public-key"));
        policy.setAllowTestKeys(true);
        policy.setStartupTimeout(Duration.ofSeconds(5));
        engine = PolicyEngine.create(policy, null, null);
    }

    @AfterAll
    static void stop() {
        engine.close();
        gateway.close();
    }

    private ResourcePolicy policy() {
        ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
        registry.register(new GetTicketTool());
        ResourceResolver<ResolvedResource> resolver = new ResourceResolver<>() {
            @Override
            public String resourceType() {
                return "ServiceDesk::Ticket";
            }

            @Override
            public Optional<ResolvedResource> fetch(String id) {
                asked.add(id);
                return Optional.ofNullable(tickets.get(id));
            }

            @Override
            public ResolvedResource describe(ResolvedResource fetched) {
                return fetched;
            }
        };
        return new ResourcePolicy(engine, new ResourceResolvers(List.of(resolver)), registry,
                new AuditLogger("test.scope.grant", "mcp-servicedesk"), "mcp-servicedesk", "servicedesk", java.util.List.of("service-account"), 200);
    }

    private String call(List<String> groups, String ticketId) {
        CallerContext claims = new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "sdreader", groups, "claude-code", null, null,
                "get_ticket", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "00000000000000-0000000", null);
        try (ResourceContext context = ResourceContext.open(claims, "get_ticket", Map.of("ticketId", ticketId))) {
            policy().authorizeCall();
            return "allowed";
        } catch (SanitizedToolException refused) {
            return "{\"error\":\"" + refused.getCode() + "\",\"message\":\"" + refused.toSafeError().message() + "\"}";
        }
    }

    private static ResolvedResource ticket(String id, String queue) {
        return ResolvedResource.of("ServiceDesk::Ticket", id, Map.of("id", id))
                .withParent(ResolvedResource.of("ServiceDesk::Queue", queue, Map.of("id", queue)));
    }

    @Test
    void aCallerWithNoQueueIsRefusedBeforeTheResolverWhetherOrNotTheTicketExists() {
        tickets = Map.of("T-1", ticket("T-1", "42"));

        assertThat(call(List.of("mcp-servicedesk-read"), "T-1")).isEqualTo(DENIED);
        assertThat(call(List.of("mcp-servicedesk-read"), "T-404")).isEqualTo(DENIED);
        assertThat(asked).as("the system is not asked at all").isEmpty();
    }

    @Test
    void aTicketInAGrantedQueueIsAllowedAndOneOutsideItIsTheSameAnswerAsAMissingOne() {
        tickets = Map.of("T-1", ticket("T-1", "42"), "T-2", ticket("T-2", "7"));
        List<String> groups = List.of("mcp-servicedesk-read", "mcp-servicedesk-queue-42");

        assertThat(call(groups, "T-1")).isEqualTo("allowed");
        assertThat(call(groups, "T-2")).isEqualTo(DENIED);
        assertThat(call(groups, "T-404")).isEqualTo(DENIED);
    }

    @Test
    void aTicketOutsideTheQueuesWhoseDataTheSystemGetsWrongIsNotAnInternalError() {
        // The system reports an id the pattern of a ticket refuses: for a caller with the queue that is the server's failure,
        // for one without it the ticket must look like any other the caller cannot see.
        tickets = Map.of("T-3", ResolvedResource.of("ServiceDesk::Ticket", "T 3", Map.of("id", "T 3"))
                .withParent(ResolvedResource.of("ServiceDesk::Queue", "7", Map.of("id", "7"))),
                "T-4", ResolvedResource.of("ServiceDesk::Ticket", "T 4", Map.of("id", "T 4"))
                        .withParent(ResolvedResource.of("ServiceDesk::Queue", "42", Map.of("id", "42"))));
        List<String> groups = List.of("mcp-servicedesk-read", "mcp-servicedesk-queue-42");

        assertThat(call(groups, "T-3")).isEqualTo(DENIED);
        assertThat(call(groups, "T-4")).contains("internal_error");
    }

    @Test
    void theToolCallStillNeedsItsOwnGroup() {
        assertThatThrownBy(() -> {
            CallerContext claims = new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "x", List.of("mcp-servicedesk-queue-42"),
                    "claude-code", null, null, "get_ticket", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "00000000000000-0000000", null);
            try (ResourceContext context = ResourceContext.open(claims, "get_ticket", Map.of("ticketId", "T-1"))) {
                policy().authorizeCall();
            }
        }).isInstanceOf(SanitizedToolException.class);
    }

    private record GetTicketTool(String name, RiskLevel riskLevel, ToolAction action, List<ResourceBinding> resourceRefs)
            implements ToolCallback, RiskClassifiedTool {

        GetTicketTool() {
            this("get_ticket", RiskLevel.READ_ONLY, ToolAction.READ,
                    List.of(new ResourceBinding("ticketId", "ServiceDesk::Ticket", Void.class)));
        }

        @Override
        public String title() {
            return "Get ticket";
        }

        @Override
        public boolean resultWithoutResources() {
            return true;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name("get_ticket").description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();
        }

        @Override
        public String call(String toolInput) {
            return "";
        }
    }
}
