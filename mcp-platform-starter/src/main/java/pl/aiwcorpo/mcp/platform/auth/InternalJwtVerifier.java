package pl.aiwcorpo.mcp.platform.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import io.micrometer.core.instrument.MeterRegistry;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Verifies the gateway's internal JWT (C2, mcp-contracts 2.0.0) against the request it arrived with.
 *
 * <p>Checks, in the order of the reference receiver in mcp-contracts ({@code test/jwt-vectors.test.mjs}),
 * so that a token reports the same reason as in the contract vectors:
 * <ol>
 *   <li>token present and three segments; header is JSON with {@code alg == ES256}</li>
 *   <li>{@code kid} in the gateway JWKS; {@code test-*} ids and the published test keys (by SPKI) only with
 *       {@code allowTestKeys}; a {@code dev-*} {@code kid} only in dev mode (the {@code devstack} profile and {@code allowTestKeys})</li>
 *   <li>ES256 signature</li>
 *   <li>claims valid against {@code internal-jwt.schema.json}</li>
 *   <li>{@code iss}, {@code aud == serverId}</li>
 *   <li>{@code exp} and {@code iat} within the clock skew, {@code exp - iat} at most the maximum lifetime</li>
 *   <li>{@code jti} not seen before (recorded until {@code exp} + skew)</li>
 *   <li>service variant only for protocol requests; {@code rid == X-Request-Id}</li>
 *   <li>{@code tool} present exactly for {@code tools/call} and equal to {@code params.name}</li>
 * </ol>
 * Any failure is an {@link InternalJwtRejectedException}; there is no fallback to another credential.
 */
public final class InternalJwtVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(InternalJwtVerifier.class);

    /** Far above any real token (a few hundred bytes); bounds the work an unauthenticated caller can cause. */
    private static final String DEV_KID_PREFIX = "dev-";

    private static final int MAX_TOKEN_LENGTH = 8 * 1024;

    /**
     * @param issuer        expected {@code iss}
     * @param audience      expected {@code aud}: the server id ({@code mcp-<system>})
     * @param clockSkew     tolerance for {@code exp} and {@code iat}
     * @param maxLifetime   largest accepted {@code exp - iat}
     * @param allowTestKeys accept {@code test-*} keys and the published test keys (dev and tests only)
     * @param devstackProfile the server runs with the {@code devstack} profile; together with {@code allowTestKeys} it is dev
     *                      mode, the only one in which a {@code dev-*} key is accepted (CC-137)
     * @param receiverMode  {@link ReceiverMode#NATIVE} (default) or the sidecar of an adapter (CC-226)
     */
    public record Settings(String issuer, String audience, Duration clockSkew, Duration maxLifetime,
                           boolean allowTestKeys, boolean devstackProfile, ReceiverMode receiverMode) {

        public Settings {
            receiverMode = receiverMode == null ? ReceiverMode.NATIVE : receiverMode;
        }

        public Settings(String issuer, String audience, Duration clockSkew, Duration maxLifetime,
                        boolean allowTestKeys, boolean devstackProfile) {
            this(issuer, audience, clockSkew, maxLifetime, allowTestKeys, devstackProfile, ReceiverMode.NATIVE);
        }

        public Settings(String issuer, String audience, Duration clockSkew, Duration maxLifetime, boolean allowTestKeys) {
            this(issuer, audience, clockSkew, maxLifetime, allowTestKeys, false, ReceiverMode.NATIVE);
        }

        boolean devMode() {
            return allowTestKeys && devstackProfile;
        }
    }

    private final Settings settings;
    private final GatewayKeys keys;
    private final JtiReplayStore replayStore;
    private final Clock clock;
    private final InternalJwtClaimsSchema schema = new InternalJwtClaimsSchema();
    private final MeterRegistry meters;

    public InternalJwtVerifier(Settings settings, GatewayKeys keys, JtiReplayStore replayStore, Clock clock) {
        this(settings, keys, replayStore, clock, null);
    }

    /** @param meters counts the classifications other than {@code READ_ONLY} an adapter sidecar receives; may be {@code null} */
    public InternalJwtVerifier(Settings settings, GatewayKeys keys, JtiReplayStore replayStore, Clock clock,
                               MeterRegistry meters) {
        this.meters = meters;
        this.settings = settings;
        this.keys = keys;
        this.replayStore = replayStore;
        this.clock = clock;
    }

    public CallerContext verify(String token, RequestBinding binding) throws InternalJwtRejectedException {
        if (token == null || token.isBlank()) {
            throw reject(InternalJwtRejection.MISSING_TOKEN);
        }
        String[] parts = token.split("\\.", -1);
        if (token.length() > MAX_TOKEN_LENGTH || parts.length != 3) {
            throw reject(InternalJwtRejection.MALFORMED);
        }
        JsonNode header = decodeJson(parts[0]);
        if (header == null || !header.isObject()) {
            throw reject(InternalJwtRejection.MALFORMED);
        }
        JsonNode alg = header.get("alg");
        if (alg == null || !alg.isString() || !"ES256".equals(alg.asString())) {
            throw reject(InternalJwtRejection.ALG_NOT_ALLOWED);
        }
        JsonNode kidNode = header.get("kid");
        if (kidNode == null || !kidNode.isString()) {
            throw reject(InternalJwtRejection.UNKNOWN_KID);
        }
        String kid = kidNode.asString();
        ECKey key = findKey(kid).orElseThrow(() -> reject(InternalJwtRejection.UNKNOWN_KID));
        if (!settings.allowTestKeys() && PublishedTestKeys.isTestKey(kid, key)
                || kid.startsWith(DEV_KID_PREFIX) && !settings.devMode()) {
            throw reject(InternalJwtRejection.TEST_KEY_NOT_ALLOWED);
        }
        JsonNode typ = header.get("typ");
        if (header.has("crit") || (typ != null && !(typ.isString() && "JWT".equals(typ.asString())))) {
            throw reject(InternalJwtRejection.HEADER_NOT_ALLOWED);
        }
        if (!signatureValid(token, key)) {
            throw reject(InternalJwtRejection.BAD_SIGNATURE);
        }

        JsonNode claims = decodeJson(parts[1]);
        if (claims == null || !claims.isObject()) {
            throw reject(InternalJwtRejection.INVALID_CLAIMS);
        }
        List<String> violations = schema.violations(claims);
        if (!violations.isEmpty()) {
            LOG.debug("event=internal_jwt_claims_invalid violations={}", violations);
            throw reject(InternalJwtRejection.INVALID_CLAIMS);
        }
        boolean classified = claims.has("mcp_risk") || claims.has("mcp_action");
        if (classified && settings.receiverMode() != ReceiverMode.ADAPTER) {
            // Only the sidecar of an adapter takes a classification from the token; the schema cannot tell the receiver (CC-226).
            throw reject(InternalJwtRejection.INVALID_CLAIMS);
        }
        if (!settings.issuer().equals(claims.get("iss").asString())) {
            throw reject(InternalJwtRejection.WRONG_ISSUER);
        }
        if (!settings.audience().equals(claims.get("aud").asString())) {
            throw reject(InternalJwtRejection.WRONG_AUDIENCE);
        }

        JsonNode expNode = claims.get("exp");
        JsonNode iatNode = claims.get("iat");
        if (!expNode.canConvertToLong() || !iatNode.canConvertToLong()) {
            throw reject(InternalJwtRejection.INVALID_CLAIMS);
        }
        long exp = expNode.asLong();
        long iat = iatNode.asLong();
        long now = clock.instant().getEpochSecond();
        long skew = settings.clockSkew().toSeconds();
        if (now > exp + skew) {
            throw reject(InternalJwtRejection.EXPIRED);
        }
        if (iat > now + skew) {
            throw reject(InternalJwtRejection.NOT_YET_VALID);
        }
        if (exp - iat > settings.maxLifetime().toSeconds()) {
            throw reject(InternalJwtRejection.LIFETIME_TOO_LONG);
        }
        if (exp < iat) {
            throw reject(InternalJwtRejection.INVALID_CLAIMS);
        }

        String jti = claims.get("jti").asString();
        boolean firstUse;
        try {
            firstUse = replayStore.firstUse(jti, Instant.ofEpochSecond(exp + skew));
        } catch (JtiReplayStore.StoreUnavailableException ex) {
            throw new InternalJwtRejectedException(InternalJwtRejection.REPLAY_STORE_UNAVAILABLE, ex);
        }
        if (!firstUse) {
            throw reject(InternalJwtRejection.REPLAYED_JTI);
        }

        boolean service = CallerContext.SERVICE_SUBJECT.equals(claims.get("sub").asString());
        if (service && !binding.serviceVariantAllowed()) {
            throw reject(InternalJwtRejection.SERVICE_TOKEN_NOT_ALLOWED);
        }
        String rid = claims.get("rid").asString();
        if (!rid.equals(binding.requestIdHeader())) {
            throw reject(InternalJwtRejection.REQUEST_ID_MISMATCH);
        }
        String tool = text(claims, "tool");
        if (!service) {
            if (binding.toolRequired() && tool == null) {
                throw reject(InternalJwtRejection.TOOL_MISSING);
            }
            if (!binding.toolRequired() && tool != null) {
                throw reject(InternalJwtRejection.TOOL_NOT_ALLOWED);
            }
        }
        if (binding.kind() == RequestBinding.Kind.TOOL_CALL && !Objects.equals(tool, binding.toolName())) {
            throw reject(InternalJwtRejection.TOOL_MISMATCH);
        }
        if (settings.receiverMode() == ReceiverMode.ADAPTER && !service && binding.kind() == RequestBinding.Kind.TOOL_CALL) {
            if (!classified) {
                throw reject(InternalJwtRejection.CLASSIFICATION_MISSING);
            }
            String risk = claims.get("mcp_risk").asString();
            if (!"READ_ONLY".equals(risk)) {
                // The gateway sends only READ_ONLY to an adapter (CC-227); anything else is a gateway that does not match this build.
                LOG.warn("event=adapter_classification_not_read_only risk={} tool={}", risk, tool);
                if (meters != null) {
                    meters.counter("mcp.adapter.classification.not_read_only", "risk", risk).increment();
                }
            }
        }
        return caller(claims);
    }

    private Optional<ECKey> findKey(String kid) throws InternalJwtRejectedException {
        try {
            return keys.find(kid);
        } catch (GatewayKeys.KeysUnavailableException ex) {
            throw new InternalJwtRejectedException(InternalJwtRejection.JWKS_UNAVAILABLE, ex);
        }
    }

    private static boolean signatureValid(String token, ECKey key) {
        try {
            return JWSObject.parse(token).verify(new ECDSAVerifier(key));
        } catch (ParseException | JOSEException | IllegalStateException ex) {
            return false;
        }
    }

    private static JsonNode decodeJson(String segment) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return StrictJson.parse(bytes);
    }

    private static String text(JsonNode claims, String name) {
        JsonNode node = claims.get(name);
        return node == null || node.isNull() ? null : node.asString();
    }

    private static CallerContext caller(JsonNode claims) {
        List<String> groups = new ArrayList<>();
        claims.get("grp").forEach(group -> groups.add(group.asString()));
        JsonNode mfa = claims.get("mfa");
        return new CallerContext(
                claims.get("sub").asString(),
                text(claims, "usr"),
                groups,
                text(claims, "azp"),
                text(claims, "tool"),
                claims.get("rid").asString(),
                claims.get("prv").asString(),
                mfa == null || !mfa.canConvertToLong() ? null : mfa.asLong(),
                text(claims, "mcp_risk"),
                text(claims, "mcp_action"));
    }

    private static InternalJwtRejectedException reject(InternalJwtRejection reason) {
        return new InternalJwtRejectedException(reason);
    }
}
