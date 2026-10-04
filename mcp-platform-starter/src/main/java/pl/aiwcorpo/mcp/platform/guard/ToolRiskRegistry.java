package pl.aiwcorpo.mcp.platform.guard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Knows the {@link RiskLevel} of every tool. Purely deterministic: the answer comes from the
 * {@link ToolRisk} annotation in code (or {@link RiskClassifiedTool}), optionally raised by the
 * policy file. Nothing is inferred from a description and no model is consulted.
 *
 * <h2>Two checks made at startup</h2>
 * <ol>
 *   <li><b>Every tool must be flagged.</b> What happens to an unflagged one is decided by
 *       {@link Unclassified}; the default refuses to start, because a forgotten flag on a tool that
 *       deletes things is precisely the mistake this exists to catch.</li>
 *   <li><b>The name must not contradict the flag.</b> A tool called {@code repo_delete} flagged
 *       {@code READ_ONLY} is a bug or a lie; either way the server does not start. This is a
 *       tripwire on whole words in the name, not a control: {@code deleteuser} written as one
 *       word is not caught.</li>
 * </ol>
 */
public class ToolRiskRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRiskRegistry.class);

    /** What to do with a tool that carries no risk flag. */
    public enum Unclassified {
        /** Refuse to start (default). */
        FAIL,
        /** Start, and treat the tool as destructive: privileged callers only, human confirmation. */
        DESTRUCTIVE
    }

    /** Name fragments that only make sense on an irreversible operation. */
    private static final Set<String> DESTRUCTIVE_WORDS =
            Set.of("delete", "drop", "remove", "purge", "truncate", "destroy", "wipe", "erase");

    private final Unclassified unclassified;
    /** Unflagged tools that were let in as destructive, by name. */
    private final Map<String, RiskLevel> programmatic = new ConcurrentHashMap<>();
    /** Identity-keyed on purpose: the flag belongs to this callback object, not to its name. */
    private final Map<ToolCallback, Optional<RiskLevel>> declaredOnMethod =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    public ToolRiskRegistry(Unclassified unclassified) {
        this.unclassified = unclassified;
    }

    /**
     * Startup validation of one tool: returns its declared risk or throws, stopping the server.
     */
    public RiskLevel classify(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        Optional<RiskLevel> declared = declared(callback);
        RiskLevel risk;
        if (declared.isPresent()) {
            risk = declared.get();
        } else {
            // The policy file is not consulted here: classifying a tool is a code change that goes
            // through review. A config file may restrict a tool further, never vouch for it.
            if (unclassified == Unclassified.DESTRUCTIVE) {
                LOG.warn("event=tool_unclassified tool=\"{}\" action=treated_as_destructive", name);
                risk = RiskLevel.DESTRUCTIVE;
            } else {
                throw new IllegalStateException("Tool '" + name + "' has no risk flag. Annotate its @Tool method "
                        + "with @ToolRisk(RiskLevel.READ_ONLY | UPDATE | DESTRUCTIVE) (or implement "
                        + "RiskClassifiedTool on a hand-written ToolCallback), or set "
                        + "aiwcorpo.mcp.platform.guard.unclassified=destructive to start anyway and treat "
                        + "unflagged tools as destructive.");
            }
            programmatic.put(name, risk);
        }
        if (risk != RiskLevel.DESTRUCTIVE) {
            destructiveWordIn(name).ifPresent(word -> {
                throw new IllegalStateException("Tool '" + name + "' is flagged " + risk.label() + " but its name "
                        + "contains '" + word + "'. Flag it @ToolRisk(RiskLevel.DESTRUCTIVE) or rename it.");
            });
        }
        return risk;
    }

    /**
     * The risk to enforce for a call right now: the declared one, raised by the policy if the policy
     * says so. A policy can make a tool MORE restricted at runtime, never less — lowering the risk of
     * a destructive tool is a code change that goes through review, not an edit to a config file.
     */
    public RiskLevel effective(ToolCallback callback, GuardPolicy policy) {
        String name = callback.getToolDefinition().name();
        // Never classified (should not happen: every tool is classified at startup) -> destructive.
        RiskLevel declared = declared(callback).orElseGet(() -> programmatic.getOrDefault(name, RiskLevel.DESTRUCTIVE));
        RiskLevel override = policy.rule(name).map(GuardPolicy.ToolRule::risk).orElse(null);
        return override != null && override.atLeast(declared) ? override : declared;
    }

    /**
     * The flag declared in code for exactly this tool: on the callback itself, or on the
     * {@code @Tool} method behind it. Deliberately NOT looked up by tool name — two classes may
     * declare tools with the same name, and a tool must never inherit another one's flag.
     */
    private Optional<RiskLevel> declared(ToolCallback callback) {
        if (callback instanceof RiskClassifiedTool classified) {
            return Optional.ofNullable(classified.riskLevel());
        }
        return declaredOnMethod.computeIfAbsent(callback, ToolRiskRegistry::readMethodFlag);
    }

    private static Optional<RiskLevel> readMethodFlag(ToolCallback callback) {
        if (!(callback instanceof MethodToolCallback)) {
            return Optional.empty();
        }
        // Spring AI keeps the reflective Method private and offers no accessor. Find it by type
        // rather than by field name, so a rename upstream does not silently unflag every tool; if
        // it cannot be read at all, the tool counts as unflagged and startup fails — closed.
        for (Field field : MethodToolCallback.class.getDeclaredFields()) {
            if (field.getType() != Method.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                Method method = (Method) field.get(callback);
                ToolRisk flag = method == null ? null : AnnotatedElementUtils.findMergedAnnotation(method, ToolRisk.class);
                return flag == null ? Optional.empty() : Optional.of(flag.value());
            } catch (ReflectiveOperationException | RuntimeException ex) {
                LOG.warn("event=tool_risk_unreadable tool=\"{}\" reason=\"{}\"",
                        callback.getToolDefinition().name(), ex.toString());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> destructiveWordIn(String toolName) {
        // Split snake_case, kebab-case, camelCase, ACRONYMCase and digits into words.
        String spaced = toolName
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        for (String word : spaced.split("[^a-z]+")) {
            // "deletes" as well as "delete" — but not "deleted", which names a state, not an action.
            String singular = word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
            if (DESTRUCTIVE_WORDS.contains(word) || DESTRUCTIVE_WORDS.contains(singular)) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }
}
