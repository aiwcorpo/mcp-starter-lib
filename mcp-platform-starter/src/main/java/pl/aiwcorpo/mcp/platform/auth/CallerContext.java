package pl.aiwcorpo.mcp.platform.auth;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Who the gateway says is calling: the verified claims of the internal JWT (C2) of the current request.
 * Filled by {@link InternalJwtFilter}, available on the request thread through {@link #current()}.
 *
 * <p>The fields map one to one onto what the shared Cedar entity builder of mcp-contracts takes
 * ({@code Caller.of(sub, usr, grp, azp).withMfaAge(mfa)}),
 * so the policy engine can build its principal from this record alone.
 *
 * @param subject             {@code sub}; {@value #SERVICE_SUBJECT} for the gateway's service variant
 * @param username            {@code usr}, {@code null} for the service variant
 * @param groups              {@code grp}, only {@code mcp-*} groups; empty for the service variant
 * @param client              {@code azp}, the AI client; {@code null} for the service variant
 * @param tool                {@code tool}; present only on {@code tools/call}
 * @param requestId           {@code rid}, equal to {@code X-Request-Id}
 * @param policyRevision      {@code prv}, the revision of the gateway's policy data the call was decided with (a counter; 0: a gateway without policy data)
 * @param mfaAgeSeconds       {@code mfa}, or {@code null} when unknown
 * @param mcpRisk             {@code mcp_risk}, the administrator's classification; only in {@link ReceiverMode#ADAPTER}, otherwise {@code null}
 * @param mcpAction           {@code mcp_action}, as above
 * @param principalType       {@code pty}: {@code agent} or {@code delegated}; {@code null} for a human (CC-260)
 * @param actorSub            {@code act.sub}, the agent acting for the user in {@code subject}; only with {@code delegated}
 * @param actorClient         {@code act.azp}, the client of that agent; only with {@code delegated}
 */
public record CallerContext(String subject, String username, List<String> groups, String client,
                            String tool, String requestId, long policyRevision, Long mfaAgeSeconds,
                            String mcpRisk, String mcpAction, String principalType, String actorSub, String actorClient) {

    /** {@code pty} of an autonomous agent: {@code subject} is its service account. */
    public static final String PRINCIPAL_AGENT = "agent";

    /** {@code pty} of an agent acting for a person: {@code subject} is the person, the agent is in {@link #actorSub()}. */
    public static final String PRINCIPAL_DELEGATED = "delegated";

    /** Without the classification of an adapter and for a human; what every native server had before contracts 8.0.0. */
    public CallerContext(String subject, String username, List<String> groups, String client, String tool,
                         String requestId, long policyRevision, Long mfaAgeSeconds) {
        this(subject, username, groups, client, tool, requestId, policyRevision, mfaAgeSeconds, null, null, null, null, null);
    }

    /** For a human, with the classification of an adapter. */
    public CallerContext(String subject, String username, List<String> groups, String client,
                         String tool, String requestId, long policyRevision, Long mfaAgeSeconds,
                         String mcpRisk, String mcpAction) {
        this(subject, username, groups, client, tool, requestId, policyRevision, mfaAgeSeconds, mcpRisk, mcpAction, null, null, null);
    }

    /** {@code sub} of the gateway's service variant (calls without a user). */
    public static final String SERVICE_SUBJECT = "system:mcp-gateway";

    private static final ThreadLocal<CallerContext> CURRENT = new ThreadLocal<>();

    public CallerContext {
        groups = groups == null ? List.of() : List.copyOf(groups);
        // The verifier gets this from the schema; a caller built by hand (a test) must not make a state the contract excludes.
        if (principalType != null && !PRINCIPAL_AGENT.equals(principalType) && !PRINCIPAL_DELEGATED.equals(principalType)) {
            throw new IllegalArgumentException("principalType is agent, delegated or absent");
        }
        boolean delegated = PRINCIPAL_DELEGATED.equals(principalType);
        if (delegated != (actorSub != null && actorClient != null) || (!delegated && (actorSub != null || actorClient != null))) {
            throw new IllegalArgumentException("act (actorSub and actorClient) is present exactly when principalType is delegated");
        }
    }

    /** True for the gateway's own calls ({@code initialize}, {@code server/discover}, {@code tools/list}, {@code ping}, manifest). */
    public boolean isService() {
        return SERVICE_SUBJECT.equals(subject);
    }

    /** The value of the Cedar context attribute {@code principalType} (CC-263): {@code user} when the token has no {@code pty}. */
    public String cedarPrincipalType() {
        return principalType == null ? "user" : principalType;
    }

    public OptionalLong mfaAge() {
        return mfaAgeSeconds == null ? OptionalLong.empty() : OptionalLong.of(mfaAgeSeconds);
    }

    /** The caller of the current request, empty outside a request that passed {@link InternalJwtFilter}. */
    public static Optional<CallerContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void bind(CallerContext caller) {
        CURRENT.set(caller);
    }

    static void clear() {
        CURRENT.remove();
    }
}
