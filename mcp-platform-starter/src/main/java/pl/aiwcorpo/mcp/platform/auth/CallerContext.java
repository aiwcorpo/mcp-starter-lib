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
 * @param policyRevision      {@code prv}, the policy bundle revision the gateway decided with
 * @param mfaAgeSeconds       {@code mfa}, or {@code null} when unknown
 * @param mcpRisk             {@code mcp_risk}, the administrator's classification; only in {@link ReceiverMode#ADAPTER}, otherwise {@code null}
 * @param mcpAction           {@code mcp_action}, as above
 */
public record CallerContext(String subject, String username, List<String> groups, String client,
                            String tool, String requestId, String policyRevision, Long mfaAgeSeconds,
                            String mcpRisk, String mcpAction) {

    /** Without the classification of an adapter; what every native server has. */
    public CallerContext(String subject, String username, List<String> groups, String client, String tool,
                         String requestId, String policyRevision, Long mfaAgeSeconds) {
        this(subject, username, groups, client, tool, requestId, policyRevision, mfaAgeSeconds, null, null);
    }

    /** {@code sub} of the gateway's service variant (calls without a user). */
    public static final String SERVICE_SUBJECT = "system:mcp-gateway";

    private static final ThreadLocal<CallerContext> CURRENT = new ThreadLocal<>();

    public CallerContext {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** True for the gateway's own calls ({@code initialize}, {@code server/discover}, {@code tools/list}, {@code ping}, manifest). */
    public boolean isService() {
        return SERVICE_SUBJECT.equals(subject);
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
