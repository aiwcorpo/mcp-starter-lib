package pl.aiwcorpo.mcp.platform.guard;

import java.util.Set;

/**
 * Who is calling, and what they are cleared for. The answer to "Who am I?" and the input to
 * "Do I have the privilege?".
 *
 * @param subject       stable caller name, written to the audit log
 * @param authenticated false for the anonymous caller (no credentials presented)
 * @param maxRisk       highest {@link RiskLevel} this caller may invoke
 * @param allowTools    if non-empty, the ONLY tools this caller may invoke
 * @param denyTools     tools this caller may never invoke, whatever their risk
 */
public record CallerIdentity(String subject, boolean authenticated, RiskLevel maxRisk,
                             Set<String> allowTools, Set<String> denyTools) {

    public static final String ANONYMOUS_SUBJECT = "anonymous";

    public CallerIdentity {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (maxRisk == null) {
            throw new IllegalArgumentException("maxRisk must not be null");
        }
        allowTools = allowTools == null ? Set.of() : Set.copyOf(allowTools);
        denyTools = denyTools == null ? Set.of() : Set.copyOf(denyTools);
    }

    /** A caller who presented no credentials. Least privilege: read-only unless the policy says otherwise. */
    public static CallerIdentity anonymous(RiskLevel maxRisk) {
        return new CallerIdentity(ANONYMOUS_SUBJECT, false, maxRisk, Set.of(), Set.of());
    }
}
