package pl.aiwcorpo.mcp.platform.guard;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.annotation.AnnotatedElementUtils;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.ToolOutputSchemas;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
 *   <li><b>A title and a consistent action.</b> {@link ToolTitle} is mandatory (CC-115) and {@link ToolRisk#action()}
 *       must not contradict the risk ({@link ToolAction#resolve}).</li>
 *   <li><b>No {@code confirmation_code} argument.</b> The name belongs to the gateway (C11); the gateway removes the
 *       argument before it reaches a server.</li>
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

    /** Longest {@link ToolTitle}; the contract sets none, a title is a label. */
    public static final int MAX_TITLE_LENGTH = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> DESTRUCTIVE_WORDS =
            Set.of("delete", "drop", "remove", "purge", "truncate", "destroy", "wipe", "erase");

    private final Unclassified unclassified;
    private final Map<String, Registered> registered = new ConcurrentHashMap<>();

    private record Registered(ToolCallback tool, RiskLevel risk, ToolAction action, String title,
                              Optional<ObjectNode> outputSchema) {
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
        ToolAction action = actionOf(callback, risk);
        String title = titleOf(callback);
        noConfirmationCode(callback);
        Optional<ObjectNode> outputSchema = ToolMethods.methodOf(callback).flatMap(ToolOutputSchemas::forMethod);
        Registered previous = registered.putIfAbsent(name, new Registered(callback, risk, action, title, outputSchema));
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

    /** The Cedar action of a registered tool (C6 {@code action}). */
    public Optional<ToolAction> actionOf(String toolName) {
        Registered entry = registered.get(toolName);
        return entry == null ? Optional.empty() : Optional.of(entry.action());
    }

    /** The title of a registered tool (C6 {@code definition.title}). */
    public Optional<String> titleOf(String toolName) {
        Registered entry = registered.get(toolName);
        return entry == null ? Optional.empty() : Optional.of(entry.title());
    }

    /** The output schema of a tool whose result holds {@code UntrustedText}, see {@link ToolOutputSchemas}. */
    public Optional<ObjectNode> outputSchemaOf(String toolName) {
        Registered entry = registered.get(toolName);
        return entry == null ? Optional.empty() : entry.outputSchema();
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
        return toolRisk(callback).map(ToolRisk::value);
    }

    private static Optional<ToolRisk> toolRisk(ToolCallback callback) {
        return ToolMethods.methodOf(callback).map(method -> AnnotatedElementUtils.findMergedAnnotation(method, ToolRisk.class));
    }

    private static ToolAction actionOf(ToolCallback callback, RiskLevel risk) {
        String name = callback.getToolDefinition().name();
        ToolAction declared = callback instanceof RiskClassifiedTool classified ? classified.action()
                : toolRisk(callback).map(ToolRisk::action).orElse(ToolAction.AUTO);
        try {
            return ToolAction.resolve(declared, risk);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Tool '" + name + "': " + ex.getMessage() + ". A "
                    + risk.label() + " tool evaluates " + ToolAction.resolve(ToolAction.AUTO, risk).wireName()
                    + (risk == RiskLevel.READ_ONLY ? "" : " or admin") + " on its resources.");
        }
    }

    private static String titleOf(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        String title = callback instanceof RiskClassifiedTool classified ? classified.title()
                : ToolMethods.methodOf(callback).map(method -> AnnotatedElementUtils.findMergedAnnotation(method, ToolTitle.class))
                        .map(ToolTitle::value).orElse(null);
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("Tool '" + name + "' has no title. Annotate its @Tool method with "
                    + "@ToolTitle(\"...\") (or return a title from RiskClassifiedTool on a hand-written ToolCallback): "
                    + "the contracts require definition.title since 3.0.0 (CC-115).");
        }
        if (title.length() > MAX_TITLE_LENGTH || !title.equals(title.strip())
                || TextHygiene.firstProblem(title, true) != null) {
            throw new IllegalStateException("Tool '" + name + "': the title must be at most " + MAX_TITLE_LENGTH
                    + " characters, without leading or trailing white space, line breaks or invisible characters.");
        }
        return title;
    }

    private static void noConfirmationCode(ToolCallback callback) {
        String schema = callback.getToolDefinition().inputSchema();
        if (schema == null || schema.isBlank()) {
            return;
        }
        try {
            if (JSON.readTree(schema).path("properties").has("confirmation_code")) {
                throw new IllegalStateException("Tool '" + callback.getToolDefinition().name() + "' declares an argument "
                        + "named confirmation_code, which belongs to the gateway (C11): the gateway adds it to tools/list "
                        + "and removes it before the call reaches the server. Rename the argument.");
            }
        } catch (JacksonException ex) {
            // An unreadable schema is reported by the input validator with the tool's name.
        }
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
