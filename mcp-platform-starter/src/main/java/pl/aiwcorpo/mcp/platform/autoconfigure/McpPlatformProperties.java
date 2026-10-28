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
 *       policy:
 *         main:
 *           url: https://policies.internal/policies/main/latest.json   # C7, pulled with ETag
 *           key-id: policies-main-2026
 *           public-key-file: /etc/mcp/policies-main.pub.pem            # Ed25519, SPKI PEM
 *         overrides:                                                   # optional emergency bundle
 *           url: https://policies.internal/policies/overrides/latest.json
 *           key-id: policies-overrides-2026
 *           public-key-file: /etc/mcp/policies-overrides.pub.pem
 *         state-dir: /var/lib/mcp/policy-state                         # rollback protection across restarts
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
    private final Policy policy = new Policy();
    private final Manifest manifest = new Manifest();

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
         * How many refusals of requests without a usable token ({@code missing_token} and {@code malformed}) one process
         * reports per second, as an audit event and a log line; the rest only count in the
         * {@code mcp_server_audit_authentication_suppressed_total} metric. Such a request costs nothing, so without a cap
         * it could fill the audit stream. At least 1 (the same default as the gateway's).
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

    /** One policy bundle channel (C7): where it is pulled from and the key that signs it. */
    public static class PolicyChannel {

        /** URL of {@code latest.json}; {@code url} and {@code sigUrl} in it are file names next to it. */
        private String url;

        /** Id of the signing key; {@code test-*} only with {@code policy.allow-test-keys} in the dev profile. */
        private String keyId;

        /** The Ed25519 public key as SPKI PEM text. */
        private String publicKey;

        /** File with the public key, used when {@code public-key} is not set. */
        private String publicKeyFile;

        /** Lowest revision ever accepted on this channel (CC-066), for example raised after an incident. */
        private String minimumRevision;

        /** How often the channel is pulled; the platform default is 30 s for main and 5 s for overrides. */
        private Duration refreshInterval;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPublicKeyFile() {
            return publicKeyFile;
        }

        public void setPublicKeyFile(String publicKeyFile) {
            this.publicKeyFile = publicKeyFile;
        }

        public String getMinimumRevision() {
            return minimumRevision;
        }

        public void setMinimumRevision(String minimumRevision) {
            this.minimumRevision = minimumRevision;
        }

        public Duration getRefreshInterval() {
            return refreshInterval;
        }

        public void setRefreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }
    }

    /** {@code aiwcorpo.mcp.platform.policy.*}: the policy bundle the server evaluates resources with (C7). */
    public static class Policy {

        private final PolicyChannel main = new PolicyChannel();
        private final PolicyChannel overrides = new PolicyChannel();

        /**
         * Directory for the rollback protection across restarts (CC-066): the last revision and archive hash per
         * channel. One directory per instance. Required outside the {@code dev} profile.
         */
        private String stateDir;

        /** Accept bundles signed with the published test keys. Allowed only with the {@code dev} profile. */
        private boolean allowTestKeys = false;

        /** How long the start waits for the first main bundle (and for the overrides bundle, if one is configured). */
        private Duration startupTimeout = Duration.ofSeconds(30);

        /**
         * After a restart in which the overrides bundle was active, how long calls are refused until it is loaded again
         * (the emergency forbid rules must not vanish silently). After that the server serves with an alarm in the log.
         */
        private Duration overridesGrace = Duration.ofMinutes(2);

        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(10);

        /** Name of a Spring SSL bundle that trusts the bundle repository; JVM default when unset. */
        private String sslBundle;

        public PolicyChannel getMain() {
            return main;
        }

        public PolicyChannel getOverrides() {
            return overrides;
        }

        public String getStateDir() {
            return stateDir;
        }

        public void setStateDir(String stateDir) {
            this.stateDir = stateDir;
        }

        public boolean isAllowTestKeys() {
            return allowTestKeys;
        }

        public void setAllowTestKeys(boolean allowTestKeys) {
            this.allowTestKeys = allowTestKeys;
        }

        public Duration getStartupTimeout() {
            return startupTimeout;
        }

        public void setStartupTimeout(Duration startupTimeout) {
            this.startupTimeout = startupTimeout;
        }

        public Duration getOverridesGrace() {
            return overridesGrace;
        }

        public void setOverridesGrace(Duration overridesGrace) {
            this.overridesGrace = overridesGrace;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public String getSslBundle() {
            return sslBundle;
        }

        public void setSslBundle(String sslBundle) {
            this.sslBundle = sslBundle;
        }
    }

    /** {@code aiwcorpo.mcp.platform.manifest.*}: what the C6 manifest says about this server. */
    public static class Manifest {

        /** SemVer of the server, {@code version} of the manifest. Required. */
        private String version;

        /** {@code https://} URL of the MCP endpoint as the gateway reaches it; informational (the gateway has its own). Required. */
        private String internalUrl;

        /** {@code credential.required}: whether every tool needs a backend credential. */
        private boolean credentialRequired = true;

        /** {@code credential.acceptedTypes}: {@code user-oauth}, {@code user-secret}, {@code token-exchange}, {@code service-account}. */
        private List<String> acceptedCredentialTypes = new ArrayList<>();

        /** Where to write the manifest at startup (for the registration step of a build or deployment); unset = nowhere. */
        private String outputFile;

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getInternalUrl() {
            return internalUrl;
        }

        public void setInternalUrl(String internalUrl) {
            this.internalUrl = internalUrl;
        }

        public boolean isCredentialRequired() {
            return credentialRequired;
        }

        public void setCredentialRequired(boolean credentialRequired) {
            this.credentialRequired = credentialRequired;
        }

        public List<String> getAcceptedCredentialTypes() {
            return acceptedCredentialTypes;
        }

        public void setAcceptedCredentialTypes(List<String> acceptedCredentialTypes) {
            this.acceptedCredentialTypes = acceptedCredentialTypes;
        }

        public String getOutputFile() {
            return outputFile;
        }

        public void setOutputFile(String outputFile) {
            this.outputFile = outputFile;
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

    public Policy getPolicy() {
        return policy;
    }

    public Manifest getManifest() {
        return manifest;
    }
}
