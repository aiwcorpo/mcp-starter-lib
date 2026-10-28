package pl.aiwcorpo.mcp.platform.auth;

/**
 * Why a request was refused by {@link InternalJwtVerifier} or {@link InternalJwtFilter}. The wire codes of
 * the token defects are the {@code expected.reason} values of the mcp-contracts JWT vectors.
 */
public enum InternalJwtRejection {

    MISSING_TOKEN("missing_token"),
    MALFORMED("malformed"),
    ALG_NOT_ALLOWED("alg_not_allowed"),
    UNKNOWN_KID("unknown_kid"),
    TEST_KEY_NOT_ALLOWED("test_key_not_allowed"),
    BAD_SIGNATURE("bad_signature"),
    INVALID_CLAIMS("invalid_claims"),
    WRONG_ISSUER("wrong_issuer"),
    WRONG_AUDIENCE("wrong_audience"),
    EXPIRED("expired"),
    NOT_YET_VALID("not_yet_valid"),
    LIFETIME_TOO_LONG("lifetime_too_long"),
    REPLAYED_JTI("replayed_jti"),
    SERVICE_TOKEN_NOT_ALLOWED("service_token_not_allowed"),
    REQUEST_ID_MISMATCH("request_id_mismatch"),
    TOOL_MISSING("tool_missing"),
    TOOL_NOT_ALLOWED("tool_not_allowed"),
    TOOL_MISMATCH("tool_mismatch"),
    /**
     * The header of a token whose {@code kid} resolved to a gateway key carries {@code crit} or a {@code typ} other than
     * {@code JWT}. Not a parse failure and never capped: it takes a token that got past the key lookup. Reported with the
     * closest existing code of C8, {@code alg_not_allowed} (a header parameter the verifier does not accept); a code of its
     * own is a proposal in STATUS.
     */
    HEADER_NOT_ALLOWED("alg_not_allowed"),
    /** mTLS: no client certificate, or one with a name that is not the gateway's. */
    CLIENT_CERTIFICATE_REJECTED("client_certificate_rejected"),
    /** The gateway's JWKS could not be fetched and no usable copy is cached. Answered with 503. */
    JWKS_UNAVAILABLE("jwks_unavailable", true),
    /** The jti store (Redis) did not answer. Answered with 503. */
    REPLAY_STORE_UNAVAILABLE("replay_store_unavailable", true);

    private final String code;
    private final boolean infrastructure;

    InternalJwtRejection(String code) {
        this(code, false);
    }

    InternalJwtRejection(String code, boolean infrastructure) {
        this.code = code;
        this.infrastructure = infrastructure;
    }

    public String code() {
        return code;
    }

    /** True when the token was not judged at all because a dependency failed; the request is still refused. */
    public boolean infrastructure() {
        return infrastructure;
    }
}
