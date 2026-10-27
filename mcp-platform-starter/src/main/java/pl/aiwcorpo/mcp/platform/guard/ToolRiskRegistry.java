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
 * Knows the {@link RiskLevel} of every tool the server exposes. Purely deterministic: the answer comes from
 * the {@link ToolRisk} annotation in code (or {@link RiskClassifiedTool}); nothing is inferred from a
 * description and no model is consulted.
 *
 * <h2>Checks made when a tool is registered (at startup)</h2>
 * <ol>
 *   <li><b>Every tool must be flagged.</b> What happens to an unflagged one is decided by
 *       {@link Unclassified}; the default refuses to start.</li>
 *   <li><b>The name must not contradict the flag.</b> A tool called {@code repo_delete} flagged
 *       {@code READ_ONLY} is a bug or a lie; either way the server does not start. A tripwire on whole
 *       words in the name, not a control.</li>
 *   <li><b>Names are unique.</b> Two tools with one name would make the gateway's approval, the audit and
 *       the internal JWT's {@code tool} claim ambiguous.</li>
 * </ol>
 */
public class ToolRiskRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRiskRegistry.class);

    /** What to do with a tool that carries no risk flag. */
    public enum Unclassified {
        /** Refuse to start (default). */
        FAIL,
        /** Start, and treat the tool as destructive. */
        DESTRUCTIVE
    }

    private static final Set<String> DESTRUCTIVE_WORDS =
            Set.of("delete", "drop", "remove", "purge", "truncate", "destroy", "wipe", "erase");

    private final Unclassified unclassified;
    private final Map<String, Registered> registered = new ConcurrentHashMap<>();

    private record Registered(ToolCallback tool, RiskLevel risk) {
    }

    public ToolRiskRegistry(Unclassified unclassified) {
        this.unclassified = unclassified;
    }

    /**
     * Validates a tool and records it under its name. Idempotent for the same callback; throws, and so stops
     * the server, for an unflagged tool, a contradicting name or a second tool with the same name.
     */
    public RiskLevel register(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        Registered existing = registered.get(name);
        if (existing != null) {
            if (existing.tool() == callback) {
                return existing.risk();
            }
            throw new IllegalStateException("Two tools are named '" + name + "'. Tool names must be unique.");
        }
        RiskLevel risk = classify(callback);
        Registered previous = registered.putIfAbsent(name, new Registered(callback, risk));
        if (previous != null && previous.tool() != callback) {
            throw new IllegalStateException("Two tools are named '" + name + "'. Tool names must be unique.");
        }
        return risk;
    }

    public boolean isRegistered(String toolName) {
        return registered.containsKey(toolName);
    }

    public Optional<RiskLevel> riskOf(String toolName) {
        Registered entry = registered.get(toolName);
        return entry == null ? Optional.empty() : Optional.of(entry.risk());
    }

    private RiskLevel classify(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        Optional<RiskLevel> declared = declared(callback);
        RiskLevel risk;
        if (declared.isPresent()) {
            risk = declared.get();
        } else if (unclassified == Unclassified.DESTRUCTIVE) {
            LOG.warn("event=tool_unclassified tool=\"{}\" action=treated_as_destructive", name);
            risk = RiskLevel.DESTRUCTIVE;
        } else {
            throw new IllegalStateException("Tool '" + name + "' has no risk flag. Annotate its @Tool method "
                    + "with @ToolRisk(RiskLevel.READ_ONLY | UPDATE | DESTRUCTIVE) (or implement "
                    + "RiskClassifiedTool on a hand-written ToolCallback), or set "
                    + "aiwcorpo.mcp.platform.tools.unclassified=destructive to start anyway and treat "
                    + "unflagged tools as destructive.");
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
     * The flag declared in code for exactly this tool: on the callback itself, or on the {@code @Tool} method
     * behind it. Not looked up by name: a tool must never inherit another one's flag.
     */
    private static Optional<RiskLevel> declared(ToolCallback callback) {
        if (callback instanceof RiskClassifiedTool classified) {
            return Optional.ofNullable(classified.riskLevel());
        }
        if (!(callback instanceof MethodToolCallback)) {
            return Optional.empty();
        }
        // Spring AI keeps the reflective Method private and offers no accessor. Found by type rather than by
        // field name, so a rename upstream fails startup (tool counts as unflagged) instead of unflagging.
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
        String spaced = toolName
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        for (String word : spaced.split("[^a-z]+")) {
            // "deletes" as well as "delete", but not "deleted", which names a state, not an action.
            String singular = word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
            if (DESTRUCTIVE_WORDS.contains(word) || DESTRUCTIVE_WORDS.contains(singular)) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }
}
