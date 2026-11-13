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
 *       state-dir: /var/lib/mcp/state        # MCP_STATE_DIR: self-signed certificate, paging cursor key
 *       internal-jwt:
 *         issuer: https://mcp-gateway.internal
 *         jwks-uri: https://mcp.example.org/.well-known/mcp-jwks.json   # MCP_GATEWAY_JWKS_URI, public https
 *         replay-store: redis                # optional: redis | memory; unset: Redis when spring.data.redis.host is set, else memory
 *       credential:
 *         connect-url: http://localhost:18090/connections?system={system}   # portal in the dev stack
 * </pre>
 * The gateway pushes the policy data (scope mapping, allowed clients, overrides) to {@code POST /.well-known/mcp-policy-data}, so there is
 * nothing to configure for it. TLS of the server port comes from {@code MCP_TLS_KEYSTORE}, {@code MCP_TLS_CERT} and {@code MCP_TLS_KEY},
 * {@code MCP_PLAIN_HTTP} or a certificate the starter generates (see {@code PlatformTlsEnvironment}).
 */
@ConfigurationProperties("aiwcorpo.mcp.platform")
public class McpPlatformProperties {

    /** Id of this server ({@code mcp-<system>}): expected {@code aud}, Redis key prefix, audit componentId. */
    private String serverId;

    /**
     * Directory for what the server keeps between starts: the self-signed certificate ({@code tls/}) and the key of paging cursors. Set
     * from {@code MCP_STATE_DIR}; {@value #DEFAULT_STATE_DIR} when that is not set.
     */
    private String stateDir = DEFAULT_STATE_DIR;

    public static final String DEFAULT_STATE_DIR = "/var/lib/mcp/state";

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
    private final Credential credential = new Credential();
    private final DevPat devPat = new DevPat();
    private final Tools tools = new Tools();
    private final Manifest manifest = new Manifest();
    private final Paging paging = new Paging();
    private final Capability capability = new Capability();
    private final Session session = new Session();

    /** The backend system this server serves: {@code server-id} without the {@code mcp-} prefix. */
    public String system() {
        return serverId == null || !serverId.startsWith("mcp-") ? null : serverId.substring("mcp-".length());
    }

    /** {@code aiwcorpo.mcp.platform.internal-jwt.*}: verification of the gateway's internal JWT (C2). */
    public static class InternalJwt {

        /** Expected {@code iss}. */
        private String issuer = "https://mcp-gateway.internal";

        /** The gateway's public JWKS, {@code https://<domain>/.well-known/mcp-jwks.json} ({@code MCP_GATEWAY_JWKS_URI}). Required; https outside the dev profile. */
        private String jwksUri;

        /**
         * How many refusals of requests without a usable token ({@code missing_token} and {@code malformed}) one process
         * reports per second, as an audit event and a log line; the rest only count in the
         * {@code mcp_audit_authentication_suppressed_total} metric (tags {@code component=server}, {@code reason}). Such a request costs nothing, so without a cap
         * it could fill the audit stream. At least 1 (the same default as the gateway's).
         */
        private int missingTokenEventsPerSecond = 20;

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

        /**
         * {@code native} (default): a server written on the starter, which refuses a token with {@code mcp_risk} or
         * {@code mcp_action}. {@code adapter}: the sidecar of an adapter, which takes the classification from them and
         * refuses a {@code tools/call} without them (CC-226).
         */
        private pl.aiwcorpo.mcp.platform.auth.ReceiverMode receiverMode = pl.aiwcorpo.mcp.platform.auth.ReceiverMode.NATIVE;

        public pl.aiwcorpo.mcp.platform.auth.ReceiverMode getReceiverMode() {
            return receiverMode;
        }

        public void setReceiverMode(pl.aiwcorpo.mcp.platform.auth.ReceiverMode receiverMode) {
            this.receiverMode = receiverMode;
        }

        /**
         * Where used {@code jti} values are kept. Unset: Redis when {@code spring.data.redis.host} is set ({@code MCP_REDIS_HOST}), otherwise
         * the bounded memory of the process, which is right for one instance. Several instances behind a balancer need Redis, or a token
         * could be used once on each of them.
         */
        private ReplayStore replayStore;

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

    /** {@code aiwcorpo.mcp.platform.manifest.*}: what the C6 manifest says about this server. */
    public static class Manifest {

        /** SemVer of the server, {@code version} of the manifest. Required. */
        private String version;

        /** URL of the MCP endpoint as the gateway reaches it; informational and optional (the gateway connects to the address an administrator gave it). */
        private String internalUrl;

        /** {@code credential.required}: whether every tool needs a backend credential. */
        private boolean credentialRequired = true;

        /** {@code credential.acceptedTypes}: {@code user-oauth}, {@code user-secret}, {@code token-exchange}, {@code service-account}. */
        private List<String> acceptedCredentialTypes = new ArrayList<>();

        /** Where to write the manifest at startup (the build keeps it as {@code target/mcp-manifest.json}); unset = nowhere. */
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

        /**
         * Most elements of one list in a result that are checked against the policy per call (each is a call to the
         * system by the resolver unless it was resolved already); a longer list ends the call as {@code internal_error}.
         */
        private int maxFilteredElements = 200;

        public int getMaxFilteredElements() {
            return maxFilteredElements;
        }

        public void setMaxFilteredElements(int maxFilteredElements) {
            this.maxFilteredElements = maxFilteredElements;
        }

        public DataSize getMaxResultSize() {
            return maxResultSize;
        }

        public void setMaxResultSize(DataSize maxResultSize) {
            this.maxResultSize = maxResultSize;
        }
    }

    /** {@code aiwcorpo.mcp.platform.paging.*}: cursors of paged tools ({@code CursorCodec}). */
    public static class Paging {

        /**
         * Base64 of the key of cursors, at least 32 bytes ({@code MCP_PAGING_CURSOR_KEY}). All instances of one server must have the same key,
         * otherwise a cursor issued by one is refused by another. Unset: a key generated once into {@code paging-cursor.key} of the state
         * directory and reused at every start, which is enough for one instance; several instances need this property.
         */
        private String cursorKey;

        /** How long a cursor can be used; the gateway's list cursors live as long. */
        private Duration cursorValidity = Duration.ofMinutes(15);

        public String getCursorKey() {
            return cursorKey;
        }

        public void setCursorKey(String cursorKey) {
            this.cursorKey = cursorKey;
        }

        public Duration getCursorValidity() {
            return cursorValidity;
        }

        public void setCursorValidity(Duration cursorValidity) {
            this.cursorValidity = cursorValidity;
        }
    }

    /** {@code aiwcorpo.mcp.platform.capability.*}: the {@code CapabilityProbe} beans of the server. */
    public static class Capability {

        /** How long one probe may take; a slower one counts as no answer. */
        private Duration timeout = Duration.ofSeconds(10);

        /** Time between two probes of a backend that answered. */
        private Duration interval = Duration.ofMinutes(5);

        /** Time between two probes of a backend that did not answer. */
        private Duration retryInterval = Duration.ofSeconds(30);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(Duration retryInterval) {
            this.retryInterval = retryInterval;
        }
    }

    /** {@code aiwcorpo.mcp.platform.session.*}: MCP sessions of the streamable transport. */
    public static class Session {

        /**
         * A session no request has used for this long is closed and its id answers 404. Above the gateway's upstream idle timeout
         * (30 minutes), which forgets its side of the session without a DELETE.
         */
        private Duration idleTimeout = Duration.ofHours(1);

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }
    }

    public Paging getPaging() {
        return paging;
    }

    public Session getSession() {
        return session;
    }

    public Capability getCapability() {
        return capability;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
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

    public Credential getCredential() {
        return credential;
    }

    public DevPat getDevPat() {
        return devPat;
    }

    public Tools getTools() {
        return tools;
    }

    public Manifest getManifest() {
        return manifest;
    }
}
