package pl.aiwcorpo.mcp.platform.guard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The policy enforcement point in front of every tool. Runs before the tool does and answers, in
 * order, with nothing but code and the policy file:
 *
 * <ol>
 *   <li><b>Is the tool switched on?</b> ({@code tools.<name>.enabled} — the kill switch)</li>
 *   <li><b>May this caller use it?</b> (caller's {@code max-risk} against the tool's risk flag, plus
 *       per-caller allow/deny lists)</li>
 *   <li><b>Does a human have to confirm it?</b> (destructive calls: a one-time code sent
 *       out-of-band, bound to caller + tool + exact arguments)</li>
 * </ol>
 *
 * <h2>The two ways a human confirms</h2>
 * <ul>
 *   <li><b>In-call</b>, when the client supports MCP elicitation: the call blocks, the user types the
 *       code into the client's own dialog, the call proceeds or fails.</li>
 *   <li><b>Two calls</b>, for every other client: the first call is refused with
 *       {@code confirmation_required}; the user gives the code to the agent, which repeats the call
 *       with the same arguments plus {@value #CONFIRMATION_ARGUMENT}.</li>
 * </ul>
 * In both, the code itself never travels through the model on its way to the human, and it is
 * useless for any other call.
 */
public class ToolGuard {

    /** Extra argument added to the schema of every non-read-only tool. */
    public static final String CONFIRMATION_ARGUMENT = "confirmation_code";

    /** A call that passed the guard. */
    public record Admission(CallerIdentity identity, RiskLevel risk, String toolInput, boolean confirmed) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ToolGuard.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };
    private static final int SUMMARY_LIMIT = 1_000;

    private final GuardPolicyStore policies;
    private final ToolRiskRegistry risks;
    private final ConfirmationService confirmations;
    private final ConfirmationPrompter prompter;
    /** Tools whose published schema carries {@link #CONFIRMATION_ARGUMENT}. */
    private final Set<String> acceptsCodeArgument = ConcurrentHashMap.newKeySet();
    /** Every tool validated at startup, by name. */
    private final Map<String, ToolCallback> registered = new ConcurrentHashMap<>();

    public ToolGuard(GuardPolicyStore policies, ToolRiskRegistry risks, ConfirmationService confirmations,
                     ConfirmationPrompter prompter) {
        this.policies = policies;
        this.risks = risks;
        this.confirmations = confirmations;
        this.prompter = prompter;
    }

    // -------------------------------------------------------------------------------- startup

    /**
     * Validates a tool when the server starts and returns the risk it is registered with. Throws —
     * and so stops the server — for a tool without a risk flag or with a name that contradicts it.
     */
    public RiskLevel register(ToolCallback tool) {
        GuardPolicy policy = policies.current();
        risks.classify(tool);
        RiskLevel risk = risks.effective(tool, policy);
        if (risk != RiskLevel.READ_ONLY) {
            if (declaresProperty(tool, CONFIRMATION_ARGUMENT)) {
                throw new IllegalStateException("Tool '" + tool.getToolDefinition().name() + "' has its own '"
                        + CONFIRMATION_ARGUMENT + "' parameter. That name is reserved by the platform for the "
                        + "human confirmation of destructive calls; rename the parameter.");
            }
            acceptsCodeArgument.add(tool.getToolDefinition().name());
        }
        registered.put(tool.getToolDefinition().name(), tool);
        return risk;
    }

    private static boolean declaresProperty(ToolCallback tool, String property) {
        String schema = tool.getToolDefinition().inputSchema();
        if (schema == null || schema.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> parsed = JSON.readValue(schema, MAP);
            return parsed != null && parsed.get("properties") instanceof Map<?, ?> properties
                    && properties.containsKey(property);
        } catch (JacksonException ex) {
            return false;   // publishedSchema() reports the broken schema
        }
    }

    /** True when this guard validated a tool with that name at startup. */
    public boolean isRegistered(String toolName) {
        return registered.containsKey(toolName);
    }

    /** The risk of a registered tool by name, if this guard validated one with that name. */
    public Optional<RiskLevel> registeredRisk(String toolName) {
        ToolCallback tool = registered.get(toolName);
        return tool == null ? Optional.empty() : Optional.of(riskOf(tool));
    }

    /**
     * "What may I do?" — every registered tool with the verdict this caller would get right now.
     * Same rules as {@link #admit}, evaluated without calling anything.
     */
    public List<Permission> permissionsFor(CallerIdentity identity) {
        GuardPolicy policy = policies.current();
        List<Permission> result = new ArrayList<>();
        for (Map.Entry<String, ToolCallback> entry : new TreeMap<>(registered).entrySet()) {
            RiskLevel risk = risks.effective(entry.getValue(), policy);
            Denial denial = check(entry.getKey(), risk, identity, policy);
            result.add(new Permission(entry.getKey(), risk, denial == null,
                    denial == null && needsConfirmation(entry.getKey(), risk, policy),
                    denial == null ? null : denial.code()));
        }
        return result;
    }

    /** One line of {@link #permissionsFor}. {@code denialCode} is {@code null} when allowed. */
    public record Permission(String tool, RiskLevel risk, boolean allowed, boolean needsConfirmation,
                             String denialCode) {
    }

    /** The caller a call without a resolved identity is treated as, or empty if such calls are refused. */
    public Optional<CallerIdentity> anonymousCaller() {
        RiskLevel level = policies.current().anonymousMaxRisk();
        return level == null ? Optional.empty() : Optional.of(CallerIdentity.anonymous(level));
    }

    /** The risk in force right now (the flag from code, possibly raised by the policy file). */
    public RiskLevel riskOf(ToolCallback tool) {
        return risks.effective(tool, policies.current());
    }

    /**
     * The input schema to publish: for tools that can require confirmation, the original schema plus
     * an optional {@value #CONFIRMATION_ARGUMENT} string, so a client without elicitation can still
     * complete the two-call flow.
     */
    public String publishedSchema(ToolCallback tool) {
        String original = tool.getToolDefinition().inputSchema();
        if (!acceptsCodeArgument.contains(tool.getToolDefinition().name())) {
            return original;
        }
        try {
            LinkedHashMap<String, Object> schema = original == null || original.isBlank()
                    ? new LinkedHashMap<>() : JSON.readValue(original, MAP);
            schema.putIfAbsent("type", "object");
            Object existing = schema.get("properties");
            Map<String, Object> properties = new LinkedHashMap<>();
            if (existing instanceof Map<?, ?> map) {
                map.forEach((k, v) -> properties.put(String.valueOf(k), v));
            }
            properties.putIfAbsent(CONFIRMATION_ARGUMENT, Map.of(
                    "type", "string",
                    "description", "One-time confirmation code obtained from the human user. Omit it on the "
                            + "first call; if the server answers confirmation_required, ask the user for the "
                            + "code and repeat the call with identical arguments plus this field. Never guess it."));
            schema.put("properties", properties);
            return JSON.writeValueAsString(schema);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Tool '" + tool.getToolDefinition().name()
                    + "' has an input schema that is not valid JSON", ex);
        }
    }

    // ------------------------------------------------------------------------------- per call

    /**
     * Decides one call. Returns the admission (with the arguments to pass on, minus the confirmation
     * code) or throws {@link ToolRejectedException}.
     */
    public Admission admit(ToolCallback tool, String toolInput, ToolContext toolContext) {
        String name = tool.getToolDefinition().name();
        GuardPolicy policy = policies.current();
        warnAboutUnknownTools(policy);
        RiskLevel risk = risks.effective(tool, policy);

        // 1. Who am I?
        CallerIdentity identity = CallerContext.current().orElse(null);
        if (identity == null) {
            if (policy.anonymousMaxRisk() == null) {
                throw new ToolRejectedException("unauthenticated", "Authentication is required.", null, risk);
            }
            identity = CallerIdentity.anonymous(policy.anonymousMaxRisk());
        }

        // 2. Is the tool on, and do I have the privilege?
        Denial denial = check(name, risk, identity, policy);
        if (denial != null) {
            throw new ToolRejectedException(denial.code(), denial.message(), identity, risk);
        }

        // 3. Does a human have to confirm?
        Arguments arguments = Arguments.parse(toolInput, acceptsCodeArgument.contains(name));
        if (!needsConfirmation(name, risk, policy)) {
            return new Admission(identity, risk, arguments.forTool(), false);
        }
        confirm(name, identity, risk, arguments, policy.confirmation(), toolContext);
        return new Admission(identity, risk, arguments.forTool(), true);
    }

    private record Denial(String code, String message) {
    }

    private volatile GuardPolicy lastCheckedPolicy;

    /**
     * A rule for a tool that does not exist is almost always a typo, and a typo in a kill switch
     * kills nothing. Said once per loaded policy, loudly.
     */
    private void warnAboutUnknownTools(GuardPolicy policy) {
        if (policy == lastCheckedPolicy) {
            return;
        }
        lastCheckedPolicy = policy;
        Set<String> unknown = new java.util.TreeSet<>(policy.tools().keySet());
        policy.roles().values().forEach(role -> {
            unknown.addAll(role.allowTools());
            unknown.addAll(role.denyTools());
        });
        unknown.removeAll(registered.keySet());
        if (!unknown.isEmpty()) {
            LOG.warn("event=guard_policy_unknown_tools tools={} known={} detail=\"the policy names tools this "
                    + "server does not have; those rules have no effect\"", unknown, new TreeMap<>(registered).keySet());
        }
    }

    /** Kill switch, per-caller lists, then the risk ceiling. {@code null} means allowed. */
    private static Denial check(String name, RiskLevel risk, CallerIdentity identity, GuardPolicy policy) {
        Optional<GuardPolicy.ToolRule> rule = policy.rule(name);
        if (rule.isPresent() && !rule.get().enabled()) {
            return new Denial("tool_disabled", "Tool '" + name + "' is disabled by policy.");
        }
        if (identity.denyTools().contains(name)
                || (!identity.allowTools().isEmpty() && !identity.allowTools().contains(name))) {
            return new Denial("access_denied",
                    "Caller '" + identity.subject() + "' is not permitted to use tool '" + name + "'.");
        }
        if (!identity.maxRisk().permits(risk)) {
            return new Denial("access_denied", "Tool '" + name + "' requires " + risk.label()
                    + " clearance; caller '" + identity.subject() + "' is limited to "
                    + identity.maxRisk().label() + " tools.");
        }
        return null;
    }

    private static boolean needsConfirmation(String name, RiskLevel risk, GuardPolicy policy) {
        Boolean forced = policy.rule(name).map(GuardPolicy.ToolRule::confirmation).orElse(null);
        RiskLevel requiredFrom = policy.confirmation().requiredFrom();
        return forced != null ? forced : requiredFrom != null && risk.atLeast(requiredFrom);
    }

    private void confirm(String name, CallerIdentity identity, RiskLevel risk, Arguments arguments,
                         GuardPolicy.Confirmation settings, ToolContext toolContext) {
        String fingerprint = arguments.fingerprint();

        // Second call of the two-call flow: the agent passes on the code the human gave it.
        if (arguments.code() != null) {
            requireConfirmed(confirmations.verify(identity, name, fingerprint, arguments.code()), name, identity, risk);
            return;
        }

        String summary = summarize(name, arguments.canonical());
        ConfirmationService.Challenge challenge;
        try {
            challenge = confirmations.issue(identity, name, fingerprint, summary, settings.ttl(), settings.maxAttempts());
        } catch (ConfirmationService.ThrottledException throttled) {
            long wait = Math.max(1, Duration.between(Instant.now(), throttled.getRetryAt()).toSeconds());
            throw new ToolRejectedException("confirmation_failed", "Too many wrong codes or confirmation requests. "
                    + "No new code was sent; try again in " + wait + " s. Tool '" + name + "' was not executed.",
                    identity, risk);
        }
        long seconds = Math.max(1, Duration.between(Instant.now(), challenge.expiresAt()).toSeconds());

        Optional<ConfirmationPrompter.Reply> reply = prompter.prompt(toolContext,
                "Confirmation needed for a " + risk.label() + " operation.\n\n" + summary + "\n\nA one-time code "
                        + "was sent to " + identity.subject() + ". Enter it to proceed (valid for " + seconds + " s).");
        if (reply.isPresent()) {
            if (!reply.get().accepted()) {
                confirmations.cancel(identity, name, fingerprint);
                throw new ToolRejectedException("confirmation_declined",
                        "The user did not confirm the operation. Nothing was changed.", identity, risk);
            }
            requireConfirmed(confirmations.verify(identity, name, fingerprint, reply.get().code()), name, identity, risk);
            return;
        }

        if (!arguments.codeArgumentSupported()) {
            throw new ToolRejectedException("confirmation_required", "Tool '" + name + "' needs the user's "
                    + "confirmation, and this client cannot be prompted for it. Nothing was changed.", identity, risk);
        }
        throw new ToolRejectedException("confirmation_required", "Tool '" + name + "' is " + risk.label()
                + " and needs the user's confirmation. Nothing was changed. A one-time code was sent to "
                + identity.subject() + " outside this conversation. Ask the user for that code, then call '"
                + name + "' again with exactly the same arguments plus " + CONFIRMATION_ARGUMENT
                + ". The code expires in " + seconds + " s. Do not guess the code.", identity, risk);
    }

    private static void requireConfirmed(ConfirmationService.Result result, String name,
                                         CallerIdentity identity, RiskLevel risk) {
        String problem = switch (result) {
            case CONFIRMED -> null;
            case WRONG_CODE -> "The confirmation code is wrong.";
            case LOCKED -> "Too many wrong codes; the confirmation was cancelled. Call the tool again without a "
                    + "code to request a new one.";
            case EXPIRED -> "The confirmation code has expired. Call the tool again without a code to request a new one.";
            case NO_CHALLENGE -> "There is no pending confirmation for this exact call (the arguments must be "
                    + "identical to the call that requested it). Call the tool again without a code to request one.";
        };
        if (problem != null) {
            throw new ToolRejectedException("confirmation_failed",
                    problem + " Tool '" + name + "' was not executed.", identity, risk);
        }
    }

    /**
     * What the approver is shown. The arguments are in canonical (sorted-key) form, so the model
     * cannot choose an order that pushes the part that matters out of view; and when they do not
     * fit, that is said in so many words rather than hidden behind an ellipsis.
     */
    private static String summarize(String tool, String canonicalArguments) {
        String args = canonicalArguments.replaceAll("\\s+", " ").trim();
        if (args.length() > SUMMARY_LIMIT) {
            args = args.substring(0, SUMMARY_LIMIT) + "… [TRUNCATED: " + args.length()
                    + " characters in total. You are not seeing the whole call.]";
        }
        return "Tool: " + tool + "\nArguments: " + args;
    }

    /**
     * The arguments of one call, split into what the tool should see and the confirmation code.
     */
    private record Arguments(String forTool, String code, String canonical, boolean codeArgumentSupported) {

        String fingerprint() {
            return GuardPolicy.sha256Hex(canonical);
        }

        static Arguments parse(String toolInput, boolean codeArgumentSupported) {
            String raw = toolInput == null || toolInput.isBlank() ? "{}" : toolInput;
            try {
                LinkedHashMap<String, Object> parsed = JSON.readValue(raw, MAP);
                if (parsed == null) {
                    parsed = new LinkedHashMap<>();      // the JSON literal null: no arguments
                }
                String code = null;
                String forTool = toolInput;
                if (codeArgumentSupported && parsed.containsKey(CONFIRMATION_ARGUMENT)) {
                    Object value = parsed.remove(CONFIRMATION_ARGUMENT);
                    code = value == null ? null : String.valueOf(value);
                    if (code != null && code.isBlank()) {
                        code = null;
                    }
                    forTool = JSON.writeValueAsString(parsed);
                }
                return new Arguments(forTool, code, JSON.writeValueAsString(canonical(parsed)), codeArgumentSupported);
            } catch (JacksonException ex) {
                // Not a JSON object: let the tool reject it; fingerprint the raw text so a code still binds to it.
                return new Arguments(toolInput, null, raw, codeArgumentSupported);
            }
        }

        /** Key order must not matter: {"a":1,"b":2} and {"b":2,"a":1} are the same call. */
        private static Object canonical(Object value) {
            if (value instanceof Map<?, ?> map) {
                TreeMap<String, Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
                return sorted;
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                list.forEach(item -> copy.add(canonical(item)));
                return copy;
            }
            return value;
        }
    }
}
