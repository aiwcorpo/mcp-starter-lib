package pl.aiwcorpo.mcp.platform.guard;

import java.util.Locale;

/**
 * How much damage a tool can do. The whole guard hangs off this one, deliberately coarse, ordering:
 * a caller cleared for a level may call every tool at or below it.
 *
 * <p>There is intentionally no level above {@link #DESTRUCTIVE} and no "delete" shortcut: anything
 * that cannot be undone is destructive, and destructive calls go through human confirmation.
 */
public enum RiskLevel {

    /** Observes state only. Safe to repeat, safe to grant by default. */
    READ_ONLY("read-only"),

    /** Changes state, but the change can be reverted (rename, comment, transition). */
    UPDATE("update"),

    /** Changes state irreversibly (drop, purge, overwrite without history). */
    DESTRUCTIVE("destructive");

    private final String label;

    RiskLevel(String label) {
        this.label = label;
    }

    /** Stable lowercase name used in the policy file, the audit log and error messages. */
    public String label() {
        return label;
    }

    /** True when a caller cleared for {@code this} level may invoke a tool of {@code toolRisk}. */
    public boolean permits(RiskLevel toolRisk) {
        return this.ordinal() >= toolRisk.ordinal();
    }

    public boolean atLeast(RiskLevel other) {
        return this.ordinal() >= other.ordinal();
    }

    /**
     * Lenient parse for policy files: {@code read-only}, {@code READ_ONLY} and {@code readonly} all
     * work, and so do the long names {@code update-undestructive} and {@code update-destructive}.
     */
    public static RiskLevel parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("risk level is missing");
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (normalized) {
            case "readonly", "read" -> READ_ONLY;
            case "update", "write", "updateundestructive", "updatenondestructive" -> UPDATE;
            case "destructive", "updatedestructive" -> DESTRUCTIVE;
            default -> throw new IllegalArgumentException(
                    "unknown risk level '" + text + "' (expected read-only, update or destructive)");
        };
    }
}
