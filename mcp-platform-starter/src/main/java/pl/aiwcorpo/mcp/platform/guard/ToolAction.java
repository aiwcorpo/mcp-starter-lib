package pl.aiwcorpo.mcp.platform.guard;

import java.util.Locale;

/**
 * The Cedar action the server evaluates on the resources of a tool (C6 {@code action}, C7): {@code read},
 * {@code update}, {@code delete} or {@code admin}. Declared in {@link ToolRisk#action()}; {@link #AUTO} takes the action
 * from the risk.
 */
public enum ToolAction {

    /** {@code read} for READ_ONLY, {@code update} for UPDATE, {@code delete} for DESTRUCTIVE. */
    AUTO,
    READ,
    UPDATE,
    DELETE,
    ADMIN;

    /** The value in the manifest and in the Cedar request, for example {@code delete}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The action of a tool: the declared one, or the one the risk implies. A declaration that contradicts the risk
     * stops the server: a READ_ONLY tool can only {@code read}, an UPDATE tool {@code update} or {@code admin}, a
     * DESTRUCTIVE tool {@code delete} or {@code admin}. The policy of a bundle (C7) grants these actions separately, so a
     * tool whose action is milder than its risk would be evaluated by the rules of a milder operation.
     *
     * @throws IllegalArgumentException when the declaration contradicts the risk
     */
    public static ToolAction resolve(ToolAction declared, RiskLevel risk) {
        ToolAction implied = switch (risk) {
            case READ_ONLY -> READ;
            case UPDATE -> UPDATE;
            case DESTRUCTIVE -> DELETE;
        };
        if (declared == null || declared == AUTO) {
            return implied;
        }
        boolean allowed = declared == implied || (declared == ADMIN && risk != RiskLevel.READ_ONLY);
        if (!allowed) {
            throw new IllegalArgumentException("action " + declared.wireName() + " contradicts risk " + risk.name());
        }
        return declared;
    }
}
