package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * CC-146c: a resource named in an argument that lies outside the virtual server link is {@code not_found_or_denied}, byte for byte the answer for one that
 * does not exist, and not {@code access_denied}; the audit keeps the reason {@code link_scope}. The bundle is the one of the contracts, with the rule
 * {@code vs-narrow-call-servicedesk-queue} of {@code cedar/examples/virtual-server.cedar}.
 */
class LinkScopeTest {

    private static TestGateway gateway;
    private static PolicyEngine engine;

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

    private static ResourcePolicy policy() {
        ResourceResolver<ResolvedResource> resolver = new ResourceResolver<>() {
            @Override
            public String resourceType() {
                return "ServiceDesk::Queue";
            }

            @Override
            public Optional<ResolvedResource> fetch(String id) {
                return List.of("7", "42").contains(id) ? Optional.of(ResolvedResource.of("ServiceDesk::Queue", id, Map.of("id", id))) : Optional.empty();
            }

            @Override
            public ResolvedResource describe(ResolvedResource fetched) {
                return fetched;
            }
        };
        ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
        registry.register(new RefTool("get_queue", "queueId", "ServiceDesk::Queue"));
        return new ResourcePolicy(engine, new ResourceResolvers(List.of(resolver)), registry,
                new AuditLogger("test.link.scope", "mcp-servicedesk"), "mcp-servicedesk", "servicedesk", List.of("user-secret"), 200);
    }

    private static String answer(String queueId, List<String> audit) {
        Logger log = (Logger) LoggerFactory.getLogger("test.link.scope");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        log.addAppender(lines);
        CallerContext claims = new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "sdreader",
                List.of("mcp-servicedesk-read", "mcp-servicedesk-queue-42", "mcp-servicedesk-queue-7"), "claude-code", "queue-42-only",
                Map.of("servicedeskQueues", List.of("42")), "get_queue", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "00000000000000-0000000", null);
        try (ResourceContext context = ResourceContext.open(claims, "get_queue", Map.of("queueId", queueId))) {
            policy().authorizeCall();
            return "allowed";
        } catch (SanitizedToolException refused) {
            return "{\"error\":\"" + refused.getCode() + "\",\"message\":\"" + refused.toSafeError().message() + "\"}";
        } finally {
            log.detachAppender(lines);
            lines.list.forEach(line -> audit.add(line.getFormattedMessage()));
        }
    }

    @Test
    void aQueueOutsideTheLinkIsNotFoundOrDeniedLikeAMissingOneAndTheAuditSaysLinkScope() {
        List<String> outsideAudit = new java.util.ArrayList<>();
        List<String> missingAudit = new java.util.ArrayList<>();

        String outside = answer("7", outsideAudit);
        String missing = answer("999", missingAudit);

        assertThat(outside).isEqualTo(missing).contains("\"error\":\"not_found_or_denied\"");
        assertThat(outsideAudit).anySatisfy(line -> assertThat(line).contains("\"event\":\"policy_decision\"", "\"reason\":\"link_scope\"",
                "\"errorCode\":\"not_found_or_denied\""));
    }

    @Test
    void aQueueInsideTheLinkPasses() {
        assertThat(answer("42", new java.util.ArrayList<>())).isEqualTo("allowed");
    }
}
