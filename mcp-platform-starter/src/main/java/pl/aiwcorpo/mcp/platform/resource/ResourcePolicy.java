package pl.aiwcorpo.mcp.platform.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import pl.aiwcorpo.mcp.cedar.entities.Caller;
import pl.aiwcorpo.mcp.cedar.entities.CedarRequest;
import pl.aiwcorpo.mcp.cedar.entities.EntityBuilder;
import pl.aiwcorpo.mcp.cedar.entities.EntityBuilderException;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.cedar.entities.ResourceIds;
import pl.aiwcorpo.mcp.cedar.entities.ToolDescriptor;
import pl.aiwcorpo.mcp.cedar.entities.VirtualServerScope;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.policy.PolicyUnavailableException;
import pl.aiwcorpo.mcp.policy.bundle.PolicyStore.Snapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the server applies the policy of the signed bundle (C7) to what a call touches. The platform calls
 * {@link #authorizeCall} before every tool runs and {@link #filterResult} after it; a tool uses the rest
 * ({@link ResultFilter}, {@link #require}, {@link #queryScope}) for resources it finds itself.
 *
 * <p>Before the tool runs, in this order, all from the one bundle the gateway decides with:
 * <ol>
 *   <li>{@code Action::"call"} on the tool, built from the token's claims and the arguments exactly like the gateway
 *       does it; a refusal is {@code access_denied}. The token's {@code prv} newer than the loaded bundle makes the
 *       server pull first.</li>
 *   <li>For a tool with {@link ResourceRef} arguments: <b>every value of every such argument</b> (each element of a list;
 *       the source and the target of a transfer) is resolved by its {@link ResourceResolver} and evaluated with the
 *       tool's action on the resource as the system reports it, with its parent. The first value that is not allowed, or
 *       cannot be resolved, ends the call with {@code not_found_or_denied}, the answer for a missing resource too. A tool
 *       that declares {@link ResourceRef} arguments and gets no value for them is refused as well.</li>
 * </ol>
 * A resource without the classification attribute the contract requires is not built, so it is not allowed (fail-closed).
 * A failure of the system while resolving ({@code upstream_unavailable}, a missing credential) is reported as that
 * failure, not as a denial. No policy loaded (or the emergency bundle awaited) is {@code upstream_unavailable}.
 */
public final class ResourcePolicy implements ResultFilter, SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(ResourcePolicy.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NOT_FOUND = "The resource does not exist or you do not have access to it.";

    private final PolicyEngine engine;
    private final ResourceResolvers resolvers;
    private final ToolRiskRegistry registry;
    private final AuditLogger audit;
    private final String serverId;
    private final String system;

    public ResourcePolicy(PolicyEngine engine, ResourceResolvers resolvers, ToolRiskRegistry registry, AuditLogger audit,
                          String serverId, String system) {
        this.engine = engine;
        this.resolvers = resolvers;
        this.registry = registry;
        this.audit = audit;
        this.serverId = serverId;
        this.system = system;
    }

    /** Every resolver a tool names must exist, now and not at the first call. */
    @Override
    public void afterSingletonsInstantiated() {
        for (String tool : registry.toolNames()) {
            for (ResourceBinding binding : registry.resourceRefsOf(tool)) {
                resolvers.forRef(binding.type(), binding.resolver(), "@Tool " + tool + "(" + binding.argument() + ")");
            }
            checkShape(tool, registry.resultShapeOf(tool));
        }
    }

    private void checkShape(String tool, ResourceRefs.ResultShape shape) {
        switch (shape) {
            case ResourceRefs.Element element -> {
                resolvers.forRef(element.type(), element.resolver(), "@Tool " + tool + " result." + element.idComponent());
                checkShape(tool, element.inside());
            }
            case ResourceRefs.Many many -> checkShape(tool, many.element());
            case ResourceRefs.Fields fields -> fields.fields().values().forEach(inner -> checkShape(tool, inner));
            case ResourceRefs.None none -> { }
        }
    }

    // ---- before the tool runs ------------------------------------------------------------------------------

    /**
     * The checks that precede a tool. Call it with the {@link ResourceContext} of the call open.
     *
     * @throws SanitizedToolException {@code access_denied}, {@code not_found_or_denied} or {@code upstream_unavailable}
     * @throws InvalidToolArgumentException for an identifier that does not match the pattern of its type
     */
    public void authorizeCall() {
        ResourceContext context = ResourceContext.require();
        CallerContext caller = context.caller();
        String tool = context.tool();
        engine.hintRevision(caller.policyRevision());
        Snapshot snapshot = snapshot();
        Caller user = user(caller);
        ToolDescriptor descriptor = descriptor(tool);
        pl.aiwcorpo.mcp.cedar.entities.ResultFilter.Authorizer authorizer = engine.authorizer(snapshot);

        boolean toolAllowed;
        try {
            CedarRequest request = snapshot.entityBuilder().forToolCall(user, descriptor, context.arguments());
            toolAllowed = authorizer.isAllowed(request);
        } catch (EntityBuilderException ex) {
            throw builderFailure(ex);
        } catch (Exception ex) {
            LOG.warn("event=policy_evaluation_failed tool=\"{}\" detail=\"{}\"", tool, ex.getClass().getName());
            toolAllowed = false;
        }
        if (!toolAllowed) {
            deny(caller, tool, null, null, null, "policy_forbid", snapshot, ToolErrorCode.ACCESS_DENIED,
                    "You are not allowed to use this tool.");
        }
        if (descriptor.resourceRefs().isEmpty()) {
            return;
        }
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        pl.aiwcorpo.mcp.cedar.entities.ResultFilter filter =
                new pl.aiwcorpo.mcp.cedar.entities.ResultFilter(snapshot.entityBuilder(), authorizer);
        pl.aiwcorpo.mcp.cedar.entities.ResultFilter.CheckResult result = filter.checkAll(user, descriptor, context.arguments(),
                (type, id) -> resolve(type, id, failure), Optional.empty());
        if (result.allowed()) {
            return;
        }
        String reason = result.reason().orElse("denied");
        if ("invalid_argument".equals(reason)) {
            throw new InvalidToolArgumentException("A resource identifier in the arguments is not valid.");
        }
        if ("not_resolved".equals(reason) && failure.get() != null) {
            throw failure.get();
        }
        LOG.info("event=resource_refused tool=\"{}\" reason={} type={}", tool, reason, result.deniedType().orElse("-"));
        deny(caller, tool, result.deniedType().orElse(null), result.deniedId().orElse(null), null,
                "denied".equals(reason) ? "policy_forbid" : "not_granted", snapshot, ToolErrorCode.NOT_FOUND_OR_DENIED, NOT_FOUND);
    }

    private ResolvedResource resolve(String type, String id, AtomicReference<RuntimeException> failure) {
        ResourceBinding binding = registry.resourceRefsOf(ResourceContext.require().tool()).stream()
                .filter(candidate -> candidate.type().equals(type)).findFirst().orElse(null);
        ResourceResolver<?> resolver = resolvers.forRef(type, binding == null ? Void.class : binding.resolver(), "tool");
        try {
            return resolvers.resolve(resolver, type, id).orElse(null);
        } catch (ResourceResolvers.ResolverFailedException ex) {
            failure.set(ex);
            return null;
        }
    }

    // ---- for tools -------------------------------------------------------------------------------------------

    /** True when the caller may do the tool's action on the resource. Fail-closed on any error. */
    public boolean allows(ResolvedResource resource) {
        try {
            ResourceContext context = ResourceContext.require();
            Snapshot snapshot = snapshot();
            return engine.authorizer(snapshot).isAllowed(snapshot.entityBuilder().forResource(user(context.caller()),
                    descriptor(context.tool()), context.arguments(), resource));
        } catch (PolicyUnavailableException ex) {
            throw unavailable();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Refuses the call unless the resource is allowed, for a resource the tool finds itself (the project an issue is
     * created in, the page a link points to).
     *
     * @throws SanitizedToolException {@code not_found_or_denied}
     */
    public void require(ResolvedResource resource) {
        if (!allows(resource)) {
            ResourceContext context = ResourceContext.require();
            deny(context.caller(), context.tool(), resource.type(), resource.id(), parentOf(resource), "policy_forbid",
                    snapshot(), ToolErrorCode.NOT_FOUND_OR_DENIED, NOT_FOUND);
        }
    }

    @Override
    public <T> List<T> retainAllowed(List<T> items, Function<? super T, ResolvedResource> toResource) {
        List<T> kept = new ArrayList<>(items.size());
        for (T item : items) {
            ResolvedResource resource;
            try {
                resource = toResource.apply(item);
            } catch (RuntimeException ex) {
                continue;
            }
            if (resource != null && allows(resource)) {
                kept.add(item);
            }
        }
        return kept;
    }

    @Override
    public <T> List<T> retainAllowedById(String resourceType, List<T> items, Function<? super T, String> idOf) {
        ResourceResolver<?> resolver = resolvers.only(resourceType).orElseThrow(() -> new IllegalStateException(
                "there is not exactly one ResourceResolver bean for " + resourceType));
        List<T> kept = new ArrayList<>(items.size());
        for (T item : items) {
            if (elementAllowed(resolver, resourceType, idOf.apply(item))) {
                kept.add(item);
            }
        }
        return kept;
    }

    private boolean elementAllowed(ResourceResolver<?> resolver, String type, String id) {
        if (id == null || !ResourceIds.isValid(type, ResourceIds.Kind.RESOURCE, id)) {
            return false;
        }
        Optional<ResolvedResource> resource = resolvers.resolve(resolver, type, id);
        return resource.isPresent() && type.equals(resource.get().type()) && allows(resource.get());
    }

    /**
     * What a query for {@code resourceType} must be limited to (a grantable type: {@code Jira::Project},
     * {@code Confluence::Space}, {@code Confluence::Page}, {@code GitLab::Group}, {@code OpenSearch::Index},
     * {@code ServiceDesk::Queue}). Built from the token's virtual server and the grants of its groups in the bundle.
     */
    public QueryScope queryScope(String resourceType) {
        ResourceContext context = ResourceContext.require();
        Snapshot snapshot = snapshot();
        try {
            return QueryScope.of(snapshot.entityBuilder().queryConstraint(user(context.caller()), resourceType));
        } catch (EntityBuilderException ex) {
            throw builderFailure(ex);
        }
    }

    // ---- after the tool ran ----------------------------------------------------------------------------------

    /**
     * Removes from the JSON result of the tool the list elements that are resources the caller may not see, and refuses
     * the call when a resource that is not a list element is not allowed. Results of tools without marked components
     * pass unchanged.
     *
     * @return the result to give to the client
     */
    public String filterResult(String toolName, String result) {
        ResourceRefs.ResultShape shape = registry.resultShapeOf(toolName);
        if (shape instanceof ResourceRefs.None || result == null) {
            return result;
        }
        JsonNode root;
        try {
            root = JSON.readTree(result);
        } catch (JacksonException ex) {
            throw new IllegalStateException("the result of tool '" + toolName + "' has resource components but is not JSON");
        }
        JsonNode filtered = apply(shape, root, true);
        return JSON.writeValueAsString(filtered);
    }

    private JsonNode apply(ResourceRefs.ResultShape shape, JsonNode node, boolean mustStay) {
        switch (shape) {
            case ResourceRefs.Many many -> {
                if (node instanceof ArrayNode array) {
                    ArrayNode kept = JSON.createArrayNode();
                    for (JsonNode element : array) {
                        if (keeps(many.element(), element)) {
                            kept.add(apply(many.element(), element, false));
                        }
                    }
                    return kept;
                }
                return node;
            }
            case ResourceRefs.Element element -> {
                if (!keeps(element, node)) {
                    throw notFound();
                }
                return apply(element.inside(), node, true);
            }
            case ResourceRefs.Fields fields -> {
                if (node instanceof ObjectNode object) {
                    fields.fields().forEach((name, inner) -> {
                        JsonNode value = object.get(name);
                        if (value != null && !value.isNull()) {
                            object.set(name, apply(inner, value, true));
                        }
                    });
                }
                return node;
            }
            case ResourceRefs.None none -> {
                return node;
            }
        }
    }

    /** Whether an element of a list stays: a resource element only when allowed; other shapes always, they are filtered inside. */
    private boolean keeps(ResourceRefs.ResultShape shape, JsonNode node) {
        if (!(shape instanceof ResourceRefs.Element element)) {
            return true;
        }
        JsonNode idNode = node.get(element.idComponent());
        String id = idNode == null ? null : idNode.isString() ? idNode.asString() : idNode.isIntegralNumber() ? idNode.asString() : null;
        ResourceResolver<?> resolver = resolvers.forRef(element.type(), element.resolver(), "result");
        return elementAllowed(resolver, element.type(), id);
    }

    // ---- shared ----------------------------------------------------------------------------------------------

    private Snapshot snapshot() {
        try {
            return engine.snapshot();
        } catch (PolicyUnavailableException ex) {
            throw unavailable();
        }
    }

    private ToolDescriptor descriptor(String tool) {
        RiskLevel risk = registry.riskOf(tool).orElseThrow(() -> new IllegalStateException("tool '" + tool + "' is not registered"));
        String action = registry.actionOf(tool).orElseThrow().wireName();
        List<pl.aiwcorpo.mcp.cedar.entities.ResourceRef> refs = registry.resourceRefsOf(tool).stream()
                .map(binding -> new pl.aiwcorpo.mcp.cedar.entities.ResourceRef(binding.argument(), binding.type())).toList();
        return new ToolDescriptor(serverId, system, tool, risk.name(), action, refs);
    }

    /** The caller of the Cedar request, from the verified claims of the internal JWT (C2), {@code vs} and {@code vsc} included. */
    static Caller user(CallerContext claims) {
        Caller caller = Caller.of(claims.subject(), claims.username(), claims.groups(), claims.client());
        if (claims.mfaAge().isPresent()) {
            caller = caller.withMfaAge(claims.mfaAge().getAsLong());
        }
        if (claims.virtualServer() != null) {
            caller = caller.withVirtualServer(VirtualServerScope.fromClaims(claims.virtualServer(),
                    claims.virtualServerScopes() == null ? Map.of() : claims.virtualServerScopes()));
        }
        return caller;
    }

    private void deny(CallerContext caller, String tool, String type, String id, String parent, String reason,
                      Snapshot snapshot, ToolErrorCode code, String message) {
        audit.policyDenied(caller, tool, registry.riskOf(tool).orElse(null), type, id, parent, reason,
                snapshot.main().revision(), code.code());
        throw new SanitizedToolException(new McpErrorMapper.SafeError(code.code(), message));
    }

    private static String parentOf(ResolvedResource resource) {
        return resource.parent().map(parent -> parent.type() + "::" + parent.id()).orElse(null);
    }

    private static SanitizedToolException notFound() {
        return new SanitizedToolException(new McpErrorMapper.SafeError(ToolErrorCode.NOT_FOUND_OR_DENIED.code(), NOT_FOUND));
    }

    private static SanitizedToolException unavailable() {
        return new SanitizedToolException(new McpErrorMapper.SafeError(ToolErrorCode.UPSTREAM_UNAVAILABLE.code(),
                "The policy is not available right now. Try again later."));
    }

    private static RuntimeException builderFailure(EntityBuilderException ex) {
        if (ex.code() == EntityBuilderException.Code.INVALID_ARGUMENT) {
            return new InvalidToolArgumentException("A resource identifier in the arguments is not valid.");
        }
        LOG.error("event=policy_request_not_built detail=\"{}\"", ex.getMessage());
        return new SanitizedToolException(new McpErrorMapper.SafeError(ToolErrorCode.INTERNAL_ERROR.code(),
                "The tool could not complete the request."));
    }
}
