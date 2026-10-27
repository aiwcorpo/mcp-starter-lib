package pl.aiwcorpo.mcp.platform.guard;

/**
 * How much damage a tool can do: {@code risk} of C6 and C8 ({@code READ_ONLY}, {@code UPDATE},
 * {@code DESTRUCTIVE}). The gateway decides who may call a tool of which risk; the server declares it.
 *
 * <p>Anything that cannot be undone is destructive; the gateway asks a human to confirm such calls (C11).
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

    /** Stable lowercase name for messages; the audit (C8) and the manifest (C6) use {@link #name()}. */
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
}
