package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;

/**
 * Configuration properties for the platform, bound from {@code aiwcorpo.mcp.platform.*}.
 *
 * <pre>
 * aiwcorpo:
 *   mcp:
 *     platform:
 *       auth-mode: oauth                 # oauth | pat — one choice for the whole server
 *       pat-header-prefix: X-PAT-        # pat mode: inbound header prefix carrying backend PATs
 *       audit-logger-name: pl.aiwcorpo.mcp.audit
 *       guard:
 *         enabled: true                   # identity + privilege + confirmation in front of every tool
 *         policy-file: file:/etc/mcp/guard-policy.yml   # hot-reloaded; unset = built-in default policy
 *         unclassified: fail              # tool without @ToolRisk: fail | destructive
 *         reload-interval: 1s
 *         whoami-tool: true               # expose the built-in platform_whoami tool
 *         oidc:                           # identity provider; unset = every caller is anonymous
 *           issuer-uri: https://login.example.com/realms/corp
 *           audiences: [mcp-server]
 * </pre>
 */
@ConfigurationProperties("aiwcorpo.mcp.platform")
public class McpPlatformProperties {

    /** Header prefix carrying per-request backend PATs, e.g. {@code X-PAT-Jira} -> system "jira". */
    private String patHeaderPrefix = "X-PAT-";

    /** Logger name the {@code AuditLogger} writes to (route to a dedicated appender -> SIEM). */
    private String auditLoggerName = "pl.aiwcorpo.mcp.audit";

    /**
     * Refuse to start when the MCP transport is asynchronous, because per-request credentials cannot
     * survive the thread hop. Turn off ONLY for a server that talks to no credentialed backend —
     * see {@code CredentialTransportGuard}.
     */
    private boolean requireSyncTransport = true;

    /**
     * How callers authenticate, for the whole server: {@code oauth} (access token from an identity
     * provider) or {@code pat} (the caller's own backend token in {@code X-PAT-<system>}).
     */
    private AuthMode authMode = AuthMode.OAUTH;

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    private final Guard guard = new Guard();

    public Guard getGuard() {
        return guard;
    }

    /** {@code aiwcorpo.mcp.platform.guard.*} — see {@code PlatformGuardAutoConfiguration}. */
    public static class Guard {

        /** Master switch. Off = 1.x behaviour: no inbound identity, no privilege check, no confirmation. */
        private boolean enabled = true;

        /**
         * Spring resource location of the policy file ({@code file:/etc/...}, {@code classpath:...}).
         * A real file is re-read when it changes. Unset = built-in default: anonymous callers are
         * read-only and destructive tools need confirmation.
         */
        private String policyFile;

        /** What to do at startup with a tool that has no risk flag. */
        private ToolRiskRegistry.Unclassified unclassified = ToolRiskRegistry.Unclassified.FAIL;

        /** How often, at most, the policy file is checked for changes. */
        private Duration reloadInterval = Duration.ofSeconds(1);

        /** Whether to expose the built-in {@code platform_whoami} tool. */
        private boolean whoamiTool = true;

        private final Oidc oidc = new Oidc();

        public Oidc getOidc() {
            return oidc;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPolicyFile() {
            return policyFile;
        }

        public void setPolicyFile(String policyFile) {
            this.policyFile = policyFile;
        }

        public ToolRiskRegistry.Unclassified getUnclassified() {
            return unclassified;
        }

        public void setUnclassified(ToolRiskRegistry.Unclassified unclassified) {
            this.unclassified = unclassified;
        }

        public Duration getReloadInterval() {
            return reloadInterval;
        }

        public void setReloadInterval(Duration reloadInterval) {
            this.reloadInterval = reloadInterval;
        }

        public boolean isWhoamiTool() {
            return whoamiTool;
        }

        public void setWhoamiTool(boolean whoamiTool) {
            this.whoamiTool = whoamiTool;
        }
    }

    /**
     * {@code aiwcorpo.mcp.platform.guard.oidc.*} — the identity provider whose access tokens this
     * server accepts. Leave both URIs unset and the server has no identity provider: every caller
     * is anonymous.
     */
    public static class Oidc {

        /**
         * The provider's issuer, e.g. {@code https://login.example.com/realms/corp}. Tokens must carry
         * exactly this {@code iss}; the signing keys are found through the provider's discovery document.
         */
        private String issuerUri;

        /** Where the signing keys are, if they should not be discovered from the issuer. */
        private String jwkSetUri;

        /**
         * Accepted {@code aud} values — the identifier this server is registered under at the provider.
         * Set it: without it, a token issued for any other application of the same provider is accepted.
         */
        private List<String> audiences = new ArrayList<>();

        /** Claim written to the audit log as the caller's name; {@code sub} is used when it is absent. */
        private String subjectClaim = "sub";

        /**
         * Claims to read roles from (a dot walks into nested objects). The defaults cover Keycloak,
         * Entra ID, Okta and Auth0; anything found is matched against {@code roles} in the policy file.
         */
        private List<String> roleClaims = new ArrayList<>(
                List.of("roles", "groups", "realm_access.roles", "permissions", "scope", "scp"));

        /** Signature algorithms accepted when {@code jwk-set-uri} is given explicitly. */
        private List<String> jwsAlgorithms = new ArrayList<>(List.of("RS256"));

        /** Public URL of this server's MCP endpoint, for the resource metadata; derived from the request if unset. */
        private String resourceUri;

        public boolean isConfigured() {
            return hasText(issuerUri) || hasText(jwkSetUri);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public List<String> getAudiences() {
            return audiences;
        }

        public void setAudiences(List<String> audiences) {
            this.audiences = audiences;
        }

        public String getSubjectClaim() {
            return subjectClaim;
        }

        public void setSubjectClaim(String subjectClaim) {
            this.subjectClaim = subjectClaim;
        }

        public List<String> getRoleClaims() {
            return roleClaims;
        }

        public void setRoleClaims(List<String> roleClaims) {
            this.roleClaims = roleClaims;
        }

        public List<String> getJwsAlgorithms() {
            return jwsAlgorithms;
        }

        public void setJwsAlgorithms(List<String> jwsAlgorithms) {
            this.jwsAlgorithms = jwsAlgorithms;
        }

        public String getResourceUri() {
            return resourceUri;
        }

        public void setResourceUri(String resourceUri) {
            this.resourceUri = resourceUri;
        }
    }

    public String getPatHeaderPrefix() {
        return patHeaderPrefix;
    }

    public void setPatHeaderPrefix(String patHeaderPrefix) {
        this.patHeaderPrefix = patHeaderPrefix;
    }

    public String getAuditLoggerName() {
        return auditLoggerName;
    }

    public void setAuditLoggerName(String auditLoggerName) {
        this.auditLoggerName = auditLoggerName;
    }

    public boolean isRequireSyncTransport() {
        return requireSyncTransport;
    }

    public void setRequireSyncTransport(boolean requireSyncTransport) {
        this.requireSyncTransport = requireSyncTransport;
    }
}
