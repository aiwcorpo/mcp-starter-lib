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
import pl.aiwcorpo.mcp.platform.text.ToolResultRejectedException;
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
 *       does it; a refusal is {@code access_denied}. The token's {@code prv} newer than the loaded bundle asks the
 *       engine to pull in the background; this call is decided with the bundle loaded now.</li>
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
    /** Elements of a list that get an audit event when removed; the rest are only counted in the log. */
    private static final int MAX_AUDITED_DROPS = 20;
    private static final String NOT_FOUND = ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE;

    private final PolicyEngine engine;
    private final ResourceResolvers resolvers;
    private final ToolRiskRegistry registry;
    private final AuditLogger audit;
    private final String serverId;
    private final String system;
    private final int maxFilteredElements;
    private final String credentialType;
    private final List<String> acceptedCredentialTypes;

    /**
     * @param credentialTypes the types the server accepts ({@code manifest.accepted-credential-types}); the first is the default one, the
     *                        {@code context.credentialType} of every request (CC-136, CC-166, CC-168); never empty
     */
    public ResourcePolicy(PolicyEngine engine, ResourceResolvers resolvers, ToolRiskRegistry registry, AuditLogger audit,
                          String serverId, String system, List<String> credentialTypes, int maxFilteredElements) {
        if (credentialTypes == null || credentialTypes.isEmpty()) {
            throw new IllegalStateException("a server has no policy without the credential type of its system (CC-136): "
                    + "set aiwcorpo.mcp.platform.manifest.accepted-credential-types");
        }
        this.acceptedCredentialTypes = List.copyOf(credentialTypes);
        this.credentialType = credentialTypes.getFirst();
        this.maxFilteredElements = maxFilteredElements;
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
        refuseOnCredentialTypeMismatch(caller, tool);
        engine.hintRevision(caller.policyRevision());
        Snapshot snapshot = snapshot();
        Caller user = user(caller);
        ToolDescriptor descriptor = descriptor(tool);
        pl.aiwcorpo.mcp.cedar.entities.ResultFilter.Authorizer authorizer = engine.authorizer(snapshot);

        boolean toolAllowed;
        try {
            CedarRequest request = builder(snapshot).forToolCall(user, descriptor, context.arguments());
            toolAllowed = authorizer.isAllowed(request);
        } catch (EntityBuilderException ex) {
            throw builderFailure(ex);
        } catch (Exception ex) {
            LOG.warn("event=policy_evaluation_failed tool=\"{}\" detail=\"{}\"", tool, ex.getClass().getName());
            throw unavailable("policy_error");
        }
        if (!toolAllowed) {
            deny(caller, tool, null, null, null, "policy_forbid", snapshot, ToolErrorCode.ACCESS_DENIED,
                    "You are not allowed to use this tool.");
        }
        if (descriptor.resourceRefs().isEmpty()) {
            return;
        }
        refuseWithoutGrant(caller, tool, descriptor, user, snapshot, authorizer, context.arguments());
        Map<String, ResolvedResource> resolved = new java.util.HashMap<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        pl.aiwcorpo.mcp.cedar.entities.ResultFilter filter =
                new pl.aiwcorpo.mcp.cedar.entities.ResultFilter(builder(snapshot), authorizer);
        pl.aiwcorpo.mcp.cedar.entities.ResultFilter.CheckResult result = filter.checkAll(user, descriptor, context.arguments(),
                (type, id) -> resolve(type, id, failure, resolved), Optional.empty());
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
        if ("evaluation_error".equals(reason) || "resource_id_invalid".equals(reason) || "resource_type_mismatch".equals(reason)) {
            ResolvedResource seen = resolved.get(result.deniedType().orElse("") + "\u0000" + result.deniedId().orElse(""));
            if (seen != null && outsideGrants(builder(snapshot), user, seen)) {
                // A resource the caller holds no grant on must not turn into a different answer than a missing one (CC-146).
                deny(caller, tool, result.deniedType().orElse(null), result.deniedId().orElse(null), null, "not_granted", snapshot,
                        ToolErrorCode.NOT_FOUND_OR_DENIED, NOT_FOUND);
            }
            // The resolver gave something the policy cannot be evaluated on: a required attribute is missing (a project
            // without category, a space without labels, a GitLab project without ancestorClassifications), the id does not
            // match its type's pattern, the type differs. Contract (entity-builder.md, section 6): internal_error. The call
            // is refused all the same; the log line above names the reason and the type, never the value.
            throw new SanitizedToolException(new McpErrorMapper.SafeError(ToolErrorCode.INTERNAL_ERROR.code(),
                    "The tool could not complete the request."));
        }
        deny(caller, tool, result.deniedType().orElse(null), result.deniedId().orElse(null), null,
                "denied".equals(reason) ? "policy_forbid" : "not_granted", snapshot, ToolErrorCode.NOT_FOUND_OR_DENIED, NOT_FOUND);
    }

    /**
     * CC-146: the grant comes first. A caller whose policy grants nothing on the container of a referenced resource gets
     * {@code not_found_or_denied} before any resolver call and before an entity is built from what the system reports (see
     * {@link GrantProbe}). A probe that cannot be built or evaluated decides nothing: the call goes on to the full check.
     */
    private void refuseWithoutGrant(CallerContext caller, String tool, ToolDescriptor descriptor, Caller user, Snapshot snapshot,
                                    pl.aiwcorpo.mcp.cedar.entities.ResultFilter.Authorizer authorizer, Map<String, Object> arguments) {
        for (pl.aiwcorpo.mcp.cedar.entities.ResourceRef ref : descriptor.resourceRefs()) {
            for (String value : GrantProbe.values(arguments.get(ref.argument()))) {
                refuseWithoutScopeGrant(caller, tool, ref.type(), value, user, snapshot);
                Optional<ResolvedResource> probe = GrantProbe.of(ref.type(), value);
                if (probe.isEmpty()) {
                    continue;
                }
                boolean granted;
                try {
                    granted = authorizer.isAllowed(builder(snapshot).forResource(user, descriptor, arguments, probe.get()));
                } catch (Exception ex) {
                    continue;
                }
                if (!granted) {
                    // The policy itself refused the probe (a forbid rule, a missing permit, the narrowing of the link): C8 policy_forbid.
                    deny(caller, tool, ref.type(), value, null, "policy_forbid", snapshot, ToolErrorCode.NOT_FOUND_OR_DENIED, NOT_FOUND);
                }
            }
        }
    }

    /**
     * CC-146 for the types whose container the identifier does not name ({@code Confluence::Page}, {@code ServiceDesk::Ticket}):
     * a caller with no scope grant on the container type gets {@code not_found_or_denied} before the resolver is asked. Here a grant is a
     * scope of the bundle ({@code mcp-confluence-space-*}, {@code mcp-servicedesk-queue-*}), not any permit of a policy.
     */
    private void refuseWithoutScopeGrant(CallerContext caller, String tool, String type, String value, Caller user, Snapshot snapshot) {
        List<String> containers = switch (type) {
            case "Confluence::Page" -> List.of("Confluence::Space", "Confluence::Page");
            case "ServiceDesk::Ticket" -> List.of("ServiceDesk::Queue");
            default -> List.of();
        };
        if (containers.isEmpty()) {
            return;
        }
        EntityBuilder builder = builder(snapshot);
        boolean any = containers.stream().anyMatch(container -> !builder.queryConstraint(user, container).granted().isEmpty());
        if (!any) {
            deny(caller, tool, type, value, null, "not_granted", snapshot, ToolErrorCode.NOT_FOUND_OR_DENIED, NOT_FOUND);
        }
    }

    /** True when the resolved page or ticket lies in no container the caller's groups grant; other types are judged by the probe. */
    private boolean outsideGrants(EntityBuilder builder, Caller user, ResolvedResource resource) {
        try {
            if ("ServiceDesk::Ticket".equals(resource.type())) {
                return resource.parent().map(queue -> !builder.queryConstraint(user, "ServiceDesk::Queue").granted().contains(queue.id()))
                        .orElse(false);
            }
            if ("Confluence::Page".equals(resource.type())) {
                boolean inSpace = resource.parent().map(space -> builder.queryConstraint(user, "Confluence::Space").granted()
                        .contains(space.id())).orElse(false);
                java.util.Set<String> pages = builder.queryConstraint(user, "Confluence::Page").granted();
                Object ancestors = resource.attributes().get("ancestorIds");
                boolean inPages = pages.contains(resource.id()) || (ancestors instanceof java.util.Collection<?> ids
                        && ids.stream().anyMatch(id -> pages.contains(String.valueOf(id))));
                return resource.parent().isPresent() && !inSpace && !inPages;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        return false;
    }

    /**
     * CC-166: a request that carries a credential must name one of the types this server accepts, checked before the policy, the grant probe and
     * the resolvers. Otherwise the policy, which is evaluated with the default type, could be switched off for the real credential (a service
     * account). Refused as {@code internal_error} (a misconfiguration, not the caller's doing) and audited as {@code credential_type_mismatch}.
     */
    private void refuseOnCredentialTypeMismatch(CallerContext caller, String tool) {
        String header = pl.aiwcorpo.mcp.platform.security.CredentialContext.typeHeader().orElse(null);
        if (!pl.aiwcorpo.mcp.platform.security.CredentialTypeCheck.admits(
                pl.aiwcorpo.mcp.platform.security.CredentialContext.credentialSent(), header, acceptedCredentialTypes)) {
            LOG.error("event=credential_type_mismatch tool=\"{}\" accepted={} header_present={}", tool, acceptedCredentialTypes, header != null);
            audit.policyRefused(caller, tool, registry.riskOf(tool).orElse(null), "credential_type_mismatch", "internal_error");
            throw new SanitizedToolException(new McpErrorMapper.SafeError(ToolErrorCode.INTERNAL_ERROR.code(),
                    "The tool could not complete the request."));
        }
    }

    private ResolvedResource resolve(String type, String id, AtomicReference<RuntimeException> failure,
                                     Map<String, ResolvedResource> resolved) {
        ResourceBinding binding = registry.resourceRefsOf(ResourceContext.require().tool()).stream()
                .filter(candidate -> candidate.type().equals(type)).findFirst().orElse(null);
        ResourceResolver<?> resolver = resolvers.forRef(type, binding == null ? Void.class : binding.resolver(), "tool");
        try {
            ResolvedResource found = resolvers.resolve(resolver, type, id).orElse(null);
            if (found != null) {
                resolved.put(type + "\u0000" + id, found);
            }
            return found;
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
            return engine.authorizer(snapshot).isAllowed(builder(snapshot).forResource(user(context.caller()),
                    descriptor(context.tool()), context.arguments(), resource));
        } catch (PolicyUnavailableException ex) {
            throw unavailable("policy_unavailable");
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
        resolvers.prefetch(resolver, resourceType, items.stream().map(idOf).filter(id -> id != null
                && ResourceIds.isValid(resourceType, ResourceIds.Kind.RESOURCE, id)).toList());
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
        Optional<ResolvedResource> resource;
        try {
            resource = resolvers.resolve(resolver, type, id);
        } catch (ResourceResolvers.ResolverFailedException ex) {
            // An element that cannot be resolved is not returned (entity-builder.md, section 8); the call goes on.
            LOG.warn("event=result_element_unresolved type={} detail=\"{}\"", type, ex.getCause().getClass().getName());
            return false;
        }
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
            return QueryScope.of(builder(snapshot).queryConstraint(user(context.caller()), resourceType));
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
        JsonNode filtered = apply(shape, root, toolName, new int[1]);
        return JSON.writeValueAsString(filtered);
    }

    private JsonNode apply(ResourceRefs.ResultShape shape, JsonNode node, String tool, int[] checked) {
        if (node == null || node.isNull()) {
            return node;
        }
        switch (shape) {
            case ResourceRefs.Many many -> {
                if (!(node instanceof ArrayNode array)) {
                    throw unexpected(tool, "a list was expected");
                }
                if (many.element() instanceof ResourceRefs.Element) {
                    checked[0] += array.size();
                }
                if (checked[0] > maxFilteredElements) {
                    throw new ToolResultRejectedException("The result of tool '" + tool + "' has " + checked[0]
                            + " elements to check against the policy, over the limit of " + maxFilteredElements
                            + " (aiwcorpo.mcp.platform.tools.max-filtered-elements); return a page of results.");
                }
                prefetch(many.element(), array);
                ArrayNode kept = JSON.createArrayNode();
                int dropped = 0;
                for (JsonNode element : array) {
                    if (keeps(many.element(), element)) {
                        kept.add(apply(many.element(), element, tool, checked));
                    } else {
                        dropped++;
                        auditDropped(many.element(), element, dropped);
                    }
                }
                if (dropped > 0) {
                    LOG.info("event=result_elements_removed tool=\"{}\" removed={}", tool, dropped);
                }
                return kept;
            }
            case ResourceRefs.Element element -> {
                requireKnownFields(element.inside(), node, tool);
                if (!keeps(element, node)) {
                    ResourceContext context = ResourceContext.require();
                    audit.policyDenied(context.caller(), tool, registry.riskOf(tool).orElse(null), element.type(),
                            idOf(element, node), null, "policy_forbid", engine.snapshot().main().revision(),
                            ToolErrorCode.NOT_FOUND_OR_DENIED.code());
                    throw notFound();
                }
                return apply(element.inside(), node, tool, checked);
            }
            case ResourceRefs.Fields fields -> {
                requireKnownFields(fields, node, tool);
                ObjectNode object = (ObjectNode) node;
                for (Map.Entry<String, ResourceRefs.ResultShape> field : fields.fields().entrySet()) {
                    JsonNode value = object.get(field.getKey());
                    if (value != null && !value.isNull()) {
                        object.set(field.getKey(), apply(field.getValue(), value, tool, checked));
                    }
                }
                return node;
            }
            case ResourceRefs.None none -> {
                return node;
            }
        }
    }

    /** One bulk fetch for the elements of a list that are resources, before each is judged. */
    private void prefetch(ResourceRefs.ResultShape shape, ArrayNode array) {
        if (!(shape instanceof ResourceRefs.Element element)) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode node : array) {
            String id = node instanceof ObjectNode ? idOf(element, node) : null;
            if (id != null && ResourceIds.isValid(element.type(), ResourceIds.Kind.RESOURCE, id)) {
                ids.add(id);
            }
        }
        resolvers.prefetch(resolvers.forRef(element.type(), element.resolver(), "result"), element.type(), ids);
    }

    /** A field the platform did not expect means the JSON is not shaped as the type says (a naming strategy, a mixin): fail closed. */
    private static void requireKnownFields(ResourceRefs.Fields fields, JsonNode node, String tool) {
        if (!(node instanceof ObjectNode object)) {
            throw unexpected(tool, "an object was expected");
        }
        for (String name : object.propertyNames()) {
            if (!fields.known().contains(name)) {
                throw unexpected(tool, "the result has a field that is not a component of its record type");
            }
        }
    }

    private static IllegalStateException unexpected(String tool, String what) {
        return new IllegalStateException("the result of tool '" + tool + "' cannot be checked against the policy: " + what
                + " (Jackson is configured to serialize records by something other than their component names)");
    }

    private String idOf(ResourceRefs.Element element, JsonNode node) {
        JsonNode idNode = node.get(element.idComponent());
        return idNode == null ? null : idNode.isString() ? idNode.asString() : idNode.isIntegralNumber() ? idNode.asString() : null;
    }

    private void auditDropped(ResourceRefs.ResultShape shape, JsonNode node, int count) {
        if (count > MAX_AUDITED_DROPS || !(shape instanceof ResourceRefs.Element element)) {
            return;
        }
        ResourceContext context = ResourceContext.require();
        audit.policyDenied(context.caller(), context.tool(), registry.riskOf(context.tool()).orElse(null), element.type(),
                idOf(element, node), null, "policy_forbid", engine.snapshot().main().revision(),
                ToolErrorCode.NOT_FOUND_OR_DENIED.code());
    }

    /** Whether an element of a list stays: a resource element only when allowed; other shapes always, they are filtered inside. */
    private boolean keeps(ResourceRefs.ResultShape shape, JsonNode node) {
        if (!(shape instanceof ResourceRefs.Element element)) {
            return true;
        }
        if (!(node instanceof ObjectNode)) {
            return false;
        }
        ResourceResolver<?> resolver = resolvers.forRef(element.type(), element.resolver(), "result");
        return elementAllowed(resolver, element.type(), idOf(element, node));
    }

    // ---- shared ----------------------------------------------------------------------------------------------

    /** The builder of the loaded bundle with the credential type of this server in every request (CC-136). */
    private EntityBuilder builder(Snapshot snapshot) {
        return snapshot.entityBuilder().withCredentialType(credentialType);
    }

    private Snapshot snapshot() {
        try {
            return engine.snapshot();
        } catch (PolicyUnavailableException ex) {
            throw unavailable("policy_unavailable");
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

    /**
     * {@code upstream_unavailable} when nothing can be decided (CC-141): no bundle is loaded ({@code policy_unavailable}) or the
     * policy engine failed on this request ({@code policy_error}). The refusal is audited as a decision with that reason.
     */
    private SanitizedToolException unavailable(String reason) {
        ResourceContext.current().ifPresent(context -> audit.policyUnavailable(context.caller(), context.tool(),
                registry.riskOf(context.tool()).orElse(null), reason));
        return new SanitizedToolException(new McpErrorMapper.SafeError(ToolErrorCode.UPSTREAM_UNAVAILABLE.code(),
                "Policies are not available. Try again later."));
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
