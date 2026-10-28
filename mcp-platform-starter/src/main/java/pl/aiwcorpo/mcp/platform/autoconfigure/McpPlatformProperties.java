package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;

/**
 * Configuration of the platform, bound from {@code aiwcorpo.mcp.platform.*}.
 *
 * <pre>
 * aiwcorpo:
 *   mcp:
 *     platform:
 *       server-id: mcp-jira                  # required: aud of the internal JWT, componentId in audit
 *       auth-mode: gateway                   # gateway | dev-pat (dev profile only)
 *       internal-jwt:
 *         issuer: https://mcp-gateway.internal
 *         jwks-uri: https://mcp-gateway.internal:8443/.well-known/jwks.json
 *         jwks-ssl-bundle: mcp-internal-ca   # Spring SSL bundle that trusts the internal CA
 *         replay-store: redis                # redis | memory (dev profile only); Redis: spring.data.redis.*
 *       mtls:
 *         required: true                     # false only in the dev profile
 *         allowed-client-names: [mcp-gateway]
 *       credential:
 *         connect-url: http://localhost:18090/connections?system={system}   # portal in mcp-platform-dev
 * </pre>
 */
@ConfigurationProperties("aiwcorpo.mcp.platform")
public class McpPlatformProperties {

    /** Id of this server ({@code mcp-<system>}): expected {@code aud}, Redis key prefix, audit componentId. */
    private String serverId;

    /** Source of the backend credential. The internal JWT is required in every mode. */
    private AuthMode authMode = AuthMode.GATEWAY;

    /** Logger the C8 audit events are written to (route it to the SIEM appender). */
    private String auditLoggerName = "pl.aiwcorpo.mcp.audit";

    /**
     * Refuse to start when the MCP transport is asynchronous: the caller and the credential live in
     * ThreadLocals filled on the servlet thread. See {@code CredentialTransportGuard}.
     */
    private boolean requireSyncTransport = true;

    private final InternalJwt internalJwt = new InternalJwt();
    private final Mtls mtls = new Mtls();
    private final Credential credential = new Credential();
    private final DevPat devPat = new DevPat();
    private final Tools tools = new Tools();

    /** The backend system this server serves: {@code server-id} without the {@code mcp-} prefix. */
    public String system() {
        return serverId == null || !serverId.startsWith("mcp-") ? null : serverId.substring("mcp-".length());
    }

    /** {@code aiwcorpo.mcp.platform.internal-jwt.*}: verification of the gateway's internal JWT (C2). */
    public static class InternalJwt {

        /** Expected {@code iss}. */
        private String issuer = "https://mcp-gateway.internal";

        /** The gateway's JWKS, {@code https://<gateway-internal>/.well-known/jwks.json}. Required. */
        private String jwksUri;

        /**
         * How many {@code missing_token} audit events one process writes per second; the rest only count in the
         * {@code mcp_server_audit_authentication_suppressed_total} metric. A request without a token costs nothing, so
         * without a cap it could fill the audit stream. At least 1 (the same default as the gateway's).
         */
        private int missingTokenEventsPerSecond = 20;

        /** Name of a Spring SSL bundle used to trust the gateway's internal endpoint; JVM default when unset. */
        private String jwksSslBundle;

        /** How long a fetched JWKS is used before it is fetched again. */
        private Duration jwksCacheTtl = Duration.ofMinutes(10);

        /**
         * How long the last fetched JWKS may still be used while the gateway cannot be reached. After that,
         * every request is refused with 503 until a fetch succeeds.
         */
        private Duration jwksMaxStale = Duration.ofHours(1);

        /** Minimum time between two fetches triggered by an unknown {@code kid}. */
        private Duration jwksRefreshMinInterval = Duration.ofSeconds(30);

        private Duration jwksConnectTimeout = Duration.ofSeconds(2);
        private Duration jwksReadTimeout = Duration.ofSeconds(5);

        /** Tolerance for {@code exp} and {@code iat}; the contract allows at most 30 s. */
        private Duration clockSkew = Duration.ofSeconds(30);

        /** Maximum {@code exp - iat}; the contract allows at most 60 s. */
        private Duration maxLifetime = Duration.ofSeconds(60);

        /**
         * Accept keys whose {@code kid} starts with {@code test-} and the keys published in mcp-contracts
         * test vectors. Allowed only with the {@code dev} profile (CC-044).
         */
        private boolean allowTestKeys = false;

        /** Where used {@code jti} values are kept. {@code memory} only with the {@code dev} profile. */
        private ReplayStore replayStore = ReplayStore.REDIS;

        /** Largest request body read to bind the token to the JSON-RPC method; larger requests get 413. */
        private DataSize maxRequestBody = DataSize.ofMegabytes(4);

        /**
         * Exact paths (relative to the context path, no patterns) served without the internal JWT. A request is
         * exempt only when its raw URI is literally one of them; see {@code InternalJwtFilter}.
         */
        private List<String> unauthenticatedPaths = new ArrayList<>(
                List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"));

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getJwksSslBundle() {
            return jwksSslBundle;
        }

        public void setJwksSslBundle(String jwksSslBundle) {
            this.jwksSslBundle = jwksSslBundle;
        }

        public Duration getJwksCacheTtl() {
            return jwksCacheTtl;
        }

        public void setJwksCacheTtl(Duration jwksCacheTtl) {
            this.jwksCacheTtl = jwksCacheTtl;
        }

        public Duration getJwksMaxStale() {
            return jwksMaxStale;
        }

        public void setJwksMaxStale(Duration jwksMaxStale) {
            this.jwksMaxStale = jwksMaxStale;
        }

        public Duration getJwksRefreshMinInterval() {
            return jwksRefreshMinInterval;
        }

        public void setJwksRefreshMinInterval(Duration jwksRefreshMinInterval) {
            this.jwksRefreshMinInterval = jwksRefreshMinInterval;
        }

        public Duration getJwksConnectTimeout() {
            return jwksConnectTimeout;
        }

        public void setJwksConnectTimeout(Duration jwksConnectTimeout) {
            this.jwksConnectTimeout = jwksConnectTimeout;
        }

        public Duration getJwksReadTimeout() {
            return jwksReadTimeout;
        }

        public void setJwksReadTimeout(Duration jwksReadTimeout) {
            this.jwksReadTimeout = jwksReadTimeout;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

        public Duration getMaxLifetime() {
            return maxLifetime;
        }

        public void setMaxLifetime(Duration maxLifetime) {
            this.maxLifetime = maxLifetime;
        }

        public boolean isAllowTestKeys() {
            return allowTestKeys;
        }

        public void setAllowTestKeys(boolean allowTestKeys) {
            this.allowTestKeys = allowTestKeys;
        }

        public ReplayStore getReplayStore() {
            return replayStore;
        }

        public void setReplayStore(ReplayStore replayStore) {
            this.replayStore = replayStore;
        }

        public DataSize getMaxRequestBody() {
            return maxRequestBody;
        }

        public void setMaxRequestBody(DataSize maxRequestBody) {
            this.maxRequestBody = maxRequestBody;
        }

        public int getMissingTokenEventsPerSecond() {
            return missingTokenEventsPerSecond;
        }

        public void setMissingTokenEventsPerSecond(int missingTokenEventsPerSecond) {
            this.missingTokenEventsPerSecond = missingTokenEventsPerSecond;
        }

        public List<String> getUnauthenticatedPaths() {
            return unauthenticatedPaths;
        }

        public void setUnauthenticatedPaths(List<String> unauthenticatedPaths) {
            this.unauthenticatedPaths = unauthenticatedPaths;
        }
    }

    public enum ReplayStore {
        REDIS,
        MEMORY
    }

    /** {@code aiwcorpo.mcp.platform.mtls.*}: the client certificate of the gateway (C3). */
    public static class Mtls {

        /**
         * Require a client certificate on every request that needs the internal JWT. TLS itself and
         * {@code server.ssl.client-auth=need} are configured by the server; this checks who presented it.
         * May be false only with the {@code dev} profile.
         */
        private boolean required = true;

        /** Accepted subject CN of the client certificate. */
        private List<String> allowedClientNames = new ArrayList<>(List.of("mcp-gateway"));

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public List<String> getAllowedClientNames() {
            return allowedClientNames;
        }

        public void setAllowedClientNames(List<String> allowedClientNames) {
            this.allowedClientNames = allowedClientNames;
        }
    }

    /** {@code aiwcorpo.mcp.platform.credential.*}. */
    public static class Credential {

        /**
         * Portal page where the user connects an account, with {@code {system}} as a placeholder. Sent in
         * {@code credential_required} (C10). Required.
         */
        private String connectUrl;

        public String getConnectUrl() {
            return connectUrl;
        }

        public void setConnectUrl(String connectUrl) {
            this.connectUrl = connectUrl;
        }
    }

    /** {@code aiwcorpo.mcp.platform.dev-pat.*}: only read in {@code auth-mode=dev-pat}. */
    public static class DevPat {

        /** Header prefix; {@code X-PAT-Jira} carries the token for system {@code jira}. */
        private String headerPrefix = "X-PAT-";

        public String getHeaderPrefix() {
            return headerPrefix;
        }

        public void setHeaderPrefix(String headerPrefix) {
            this.headerPrefix = headerPrefix;
        }
    }

    /** Default of {@code tools.max-result-size}: well above a page of results, far below what exhausts the heap. */
    public static final DataSize DEFAULT_MAX_RESULT_SIZE = DataSize.ofMegabytes(1);

    /** {@code aiwcorpo.mcp.platform.tools.*}. */
    public static class Tools {

        /** What to do at startup with a tool that has no {@code @ToolRisk}. */
        private ToolRiskRegistry.Unclassified unclassified = ToolRiskRegistry.Unclassified.FAIL;

        public ToolRiskRegistry.Unclassified getUnclassified() {
            return unclassified;
        }

        public void setUnclassified(ToolRiskRegistry.Unclassified unclassified) {
            this.unclassified = unclassified;
        }

        /**
         * Largest result (UTF-8 bytes of the text a tool returns) the platform passes on; a larger one is
         * {@code internal_error} and is not parsed. Results are parsed and checked in memory, and a tool that returns
         * a whole search without paging could otherwise cost hundreds of megabytes of heap per call.
         */
        private DataSize maxResultSize = DEFAULT_MAX_RESULT_SIZE;

        public DataSize getMaxResultSize() {
            return maxResultSize;
        }

        public void setMaxResultSize(DataSize maxResultSize) {
            this.maxResultSize = maxResultSize;
        }
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
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

    public InternalJwt getInternalJwt() {
        return internalJwt;
    }

    public Mtls getMtls() {
        return mtls;
    }

    public Credential getCredential() {
        return credential;
    }

    public DevPat getDevPat() {
        return devPat;
    }

    public Tools getTools() {
        return tools;
    }
}
