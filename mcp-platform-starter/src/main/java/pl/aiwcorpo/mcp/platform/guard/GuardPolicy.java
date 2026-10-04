package pl.aiwcorpo.mcp.platform.guard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One immutable snapshot of the guard policy. Replaced as a whole on reload, so a tool call always
 * sees a consistent policy — never half of the old file and half of the new one.
 *
 * <pre>
 * anonymous: read-only            # no token:            read-only | update | destructive | deny
 * authenticated: read-only        # OAuth mode: valid token, no role below matches
 * pat: read-only                  # PAT mode: any caller presenting an X-PAT-* header
 * roles:                          # role / group / scope from the token  ->  what it grants
 *   mcp-operator: { max-risk: update }
 *   mcp-admin:
 *     max-risk: destructive
 *     deny-tools: [repo_purge]    # optional: tools holders of this role may never use
 *     allow-tools: []             # optional: the only tools this role grants
 * tools:
 *   example_purge:
 *     enabled: true               # false = kill switch, effective on the next call
 *     risk: destructive           # may only RAISE the risk declared in code
 *     confirmation: true          # force / waive human confirmation for this tool
 * confirmation:
 *   required-from: destructive    # risk level from which a human must confirm | never
 *   ttl-seconds: 120
 *   max-attempts: 3
 * </pre>
 *
 * <p>There are no users in this file. Who a caller is comes from the identity provider's token;
 * the policy only says what a role is worth here.
 *
 * @param anonymousMaxRisk     highest risk an unauthenticated caller may invoke; {@code null} = reject them
 * @param authenticatedMaxRisk risk level of a caller with a valid token and no mapped role
 * @param patMaxRisk           PAT mode: risk level of a caller who presents a personal access token
 * @param roles                what each role grants, keyed by role name as it appears in the token
 * @param tools                per-tool rules keyed by tool name
 */
public record GuardPolicy(RiskLevel anonymousMaxRisk, RiskLevel authenticatedMaxRisk, RiskLevel patMaxRisk,
                          Map<String, RoleRule> roles,
                          Map<String, ToolRule> tools, Confirmation confirmation) {

    /**
     * @param maxRisk    highest risk this role lets its holder invoke
     * @param allowTools if non-empty, the only tools this role grants
     * @param denyTools  tools a holder of this role may never use
     */
    public record RoleRule(RiskLevel maxRisk, Set<String> allowTools, Set<String> denyTools) {
    }

    /**
     * @param enabled      {@code false} disables the tool for everyone
     * @param risk         risk override, or {@code null} to use the one declared in code
     * @param confirmation {@code true}/{@code false} to force/waive confirmation, {@code null} = default
     */
    public record ToolRule(boolean enabled, RiskLevel risk, Boolean confirmation) {
    }

    /**
     * @param requiredFrom lowest risk that needs human confirmation; {@code null} = never
     */
    public record Confirmation(RiskLevel requiredFrom, Duration ttl, int maxAttempts) {

        public static final Confirmation DEFAULT =
                new Confirmation(RiskLevel.DESTRUCTIVE, Duration.ofSeconds(120), 3);
    }

    /** No file configured at all: anonymous callers are read-only, destructive calls need confirmation. */
    public static final GuardPolicy DEFAULT =
            new GuardPolicy(RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, Map.of(), Map.of(),
                    Confirmation.DEFAULT);

    public GuardPolicy {
        roles = Map.copyOf(roles);
        tools = Map.copyOf(tools);
    }

    public Optional<ToolRule> rule(String tool) {
        return Optional.ofNullable(tools.get(tool));
    }

    /**
     * "Do I have the privilege?" — turns an authenticated subject and the roles their token carries
     * into what they may do on this server.
     *
     * <p>The caller gets the highest {@code max-risk} among their mapped roles (or
     * {@code authenticated} when none is mapped). Deny lists add up: one role denying a tool is
     * enough. Allow lists restrict only if EVERY mapped role has one, and then they add up too —
     * a role without an allow list means "no restriction", and holding it must not be undone by
     * also holding a narrower role.
     */
    public CallerIdentity identityFor(String subject, Collection<String> tokenRoles) {
        RiskLevel maxRisk = authenticatedMaxRisk;
        Set<String> allow = new HashSet<>();
        Set<String> deny = new HashSet<>();
        boolean everyRoleRestricts = true;
        boolean anyRole = false;
        for (String role : tokenRoles) {
            RoleRule rule = roles.get(role);
            if (rule == null) {
                continue;
            }
            anyRole = true;
            if (rule.maxRisk().atLeast(maxRisk)) {
                maxRisk = rule.maxRisk();
            }
            deny.addAll(rule.denyTools());
            if (rule.allowTools().isEmpty()) {
                everyRoleRestricts = false;
            } else {
                allow.addAll(rule.allowTools());
            }
        }
        return new CallerIdentity(subject, true, maxRisk, anyRole && everyRoleRestricts ? allow : Set.of(), deny);
    }

    // ---------------------------------------------------------------------------------- parsing

    /**
     * Builds a policy from the parsed YAML document. Strict on purpose: an unknown key or a value of
     * the wrong type is an error, because a misspelt {@code enabeld: false} that is silently ignored
     * is a kill switch that does not kill.
     */
    public static GuardPolicy fromYaml(Object document) {
        if (document == null) {
            // An empty file is what a half-finished save looks like. Treating it as "the default
            // policy" would silently drop every kill switch, so it is an error like any other.
            throw new IllegalArgumentException("the policy file is empty");
        }
        Map<String, Object> root = asMap(document, "policy");
        requireKnownKeys(root, "policy", Set.of("anonymous", "authenticated", "pat", "roles", "tools", "confirmation"));

        RiskLevel anonymous = RiskLevel.READ_ONLY;
        if (root.containsKey("anonymous")) {
            String value = asString(root.get("anonymous"), "anonymous");
            anonymous = "deny".equalsIgnoreCase(value.trim()) ? null : RiskLevel.parse(value);
        }

        RiskLevel authenticated = root.containsKey("authenticated")
                ? RiskLevel.parse(asString(root.get("authenticated"), "authenticated"))
                : RiskLevel.READ_ONLY;   // a valid token alone proves who you are, not what you may do

        RiskLevel pat = root.containsKey("pat")
                ? RiskLevel.parse(asString(root.get("pat"), "pat"))
                : RiskLevel.READ_ONLY;

        Map<String, RoleRule> roles = new HashMap<>();
        if (root.get("roles") != null) {
            for (Map.Entry<String, Object> role : asMap(root.get("roles"), "roles").entrySet()) {
                String where = "roles." + role.getKey();
                Map<String, Object> rule = asMap(role.getValue(), where);
                requireKnownKeys(rule, where, Set.of("max-risk", "allow-tools", "deny-tools"));
                roles.put(role.getKey(), new RoleRule(
                        RiskLevel.parse(asString(rule.get("max-risk"), where + ".max-risk")),
                        Set.copyOf(asStringSet(rule.get("allow-tools"), where + ".allow-tools")),
                        Set.copyOf(asStringSet(rule.get("deny-tools"), where + ".deny-tools"))));
            }
        }

        Map<String, ToolRule> tools = new HashMap<>();
        if (root.get("tools") != null) {
            for (Map.Entry<String, Object> tool : asMap(root.get("tools"), "tools").entrySet()) {
                String where = "tools." + tool.getKey();
                Map<String, Object> rule = tool.getValue() == null ? Map.of() : asMap(tool.getValue(), where);
                requireKnownKeys(rule, where, Set.of("enabled", "risk", "confirmation"));
                tools.put(tool.getKey(), new ToolRule(
                        !rule.containsKey("enabled") || asBoolean(rule.get("enabled"), where + ".enabled"),
                        rule.get("risk") == null ? null : RiskLevel.parse(asString(rule.get("risk"), where + ".risk")),
                        rule.get("confirmation") == null ? null
                                : asBoolean(rule.get("confirmation"), where + ".confirmation")));
            }
        }

        Confirmation confirmation = Confirmation.DEFAULT;
        if (root.get("confirmation") != null) {
            Map<String, Object> c = asMap(root.get("confirmation"), "confirmation");
            requireKnownKeys(c, "confirmation", Set.of("required-from", "ttl-seconds", "max-attempts"));
            RiskLevel from = Confirmation.DEFAULT.requiredFrom();
            if (c.containsKey("required-from")) {
                String value = asString(c.get("required-from"), "confirmation.required-from");
                from = "never".equalsIgnoreCase(value.trim()) ? null : RiskLevel.parse(value);
            }
            int ttl = c.get("ttl-seconds") == null ? 120 : asInt(c.get("ttl-seconds"), "confirmation.ttl-seconds");
            int attempts = c.get("max-attempts") == null ? 3 : asInt(c.get("max-attempts"), "confirmation.max-attempts");
            if (ttl < 1 || attempts < 1) {
                throw new IllegalArgumentException("confirmation.ttl-seconds and max-attempts must be positive");
            }
            confirmation = new Confirmation(from, Duration.ofSeconds(ttl), attempts);
        }
        return new GuardPolicy(anonymous, authenticated, pat, roles, tools, confirmation);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is mandatory on every JVM
        }
    }

    private static void requireKnownKeys(Map<String, Object> map, String where, Set<String> known) {
        for (String key : map.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException(
                        "unknown key '" + key + "' in " + where + " (allowed: " + known.stream().sorted().toList() + ")");
            }
        }
    }

    private static Map<String, Object> asMap(Object value, String where) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + " must be a mapping");
        }
        Map<String, Object> result = new HashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static List<?> asList(Object value, String where) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(where + " must be a list");
        }
        return list;
    }

    private static Set<String> asStringSet(Object value, String where) {
        Set<String> result = new HashSet<>();
        for (Object item : asList(value, where)) {
            result.add(asString(item, where + "[]"));
        }
        return result;
    }

    private static String asString(Object value, String where) {
        if (value == null) {
            throw new IllegalArgumentException(where + " is required");
        }
        if (value instanceof Map || value instanceof List) {
            throw new IllegalArgumentException(where + " must be a plain value");
        }
        return String.valueOf(value);
    }

    private static boolean asBoolean(Object value, String where) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(where + " must be true or false");
    }

    private static int asInt(Object value, String where) {
        if (value instanceof Integer i) {
            return i;
        }
        throw new IllegalArgumentException(where + " must be a whole number");
    }
}
