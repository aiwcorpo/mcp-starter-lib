package pl.aiwcorpo.mcp.platform.paging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

/**
 * The cursor a paged tool hands to the client and takes back: an opaque, encrypted and authenticated token that carries
 * the position in the backend's own paging (an offset, a {@code nextPageToken}, a keyset value such as the sort values of
 * the last hit), so a server does not invent its own cursor format and does not trust one it gets from the model.
 *
 * <p><b>The payload is confidential.</b> A position can contain values of an element the policy hid from the caller (the
 * sort values of a filtered hit are the usual case), so the cursor is AES-256-GCM, not a signed plain text: the client cannot
 * read the position, change a byte, or move the cursor to another caller, tool, query or server. The key is derived from
 * {@code aiwcorpo.mcp.platform.paging.cursor-key} with HKDF-SHA256 and the {@code server-id} as context, the nonce is random
 * per cursor, and the additional authenticated data is the {@code sub}, the tool and the query fingerprint (see
 * {@link #fingerprint(String...)}). The cursor expires. Any cursor that does not open (another user, tool, query or server,
 * forged, truncated, re-spelled or expired) is refused with the same fixed {@code invalid_argument} text, so the text tells
 * nothing about the cause.
 *
 * <p>What a cursor is not: a grant. It never carries the scope of the caller. Every page is fetched with the scope
 * computed again for the current request ({@code QueryScope}) and filtered again by the {@code ResultFilter}, so a
 * forged or replayed cursor cannot widen what a page contains. Still put in it only the backend's position, never a
 * credential: the server that opens it sees the position in clear.
 *
 * <p>Instances of one server must share {@code aiwcorpo.mcp.platform.paging.cursor-key} ({@code MCP_PAGING_CURSOR_KEY}); without it each
 * instance keeps a key of its own in its state directory, and a cursor issued by one instance is refused by another. A key that is
 * generated for the process only (the dev profile, or a state directory that cannot be written) is refused by the same instance after a restart too. The gateway's
 * own cursors (C5) are a different mechanism and are not affected.
 */
public final class CursorCodec {

    /** Largest cursor the platform accepts as an argument; publish it as {@code maxLength} of the {@code cursor} parameter. */
    public static final int MAX_CURSOR_LENGTH = 1024;

    /** Pattern of the characters a cursor consists of; publish it as {@code pattern} of the {@code cursor} parameter. */
    public static final String ARGUMENT_PATTERN = "^[A-Za-z0-9_.-]{1,1024}$";

    /** Largest position (UTF-8 bytes) a cursor can carry. */
    public static final int MAX_STATE_BYTES = 512;

    /** The only text of a refused cursor. */
    public static final String REFUSED_MESSAGE =
            "The cursor is not valid for this query or has expired. Repeat the query without a cursor.";

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "c2.";
    private static final byte[] DOMAIN = "mcp-platform-cursor-v2".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static final Logger LOG = LoggerFactory.getLogger(CursorCodec.class);

    private final SecretKeySpec key;
    private final Duration validity;
    private final Clock clock;
    private final boolean generatedKey;
    private final AtomicBoolean generatedKeyReported = new AtomicBoolean();

    public CursorCodec(byte[] key, Duration validity, Clock clock) {
        this(key, validity, clock, "", false);
    }

    /** As the three argument constructor, with the id of the server in the signature. */
    public CursorCodec(byte[] key, Duration validity, Clock clock, String serverId) {
        this(key, validity, clock, serverId, false);
    }

    /**
     * A codec with a key generated for this process: the first cursor it issues logs a warning, so a server without a
     * paged tool does not warn at all.
     */
    public static CursorCodec withGeneratedKey(byte[] key, Duration validity, Clock clock, String serverId) {
        return new CursorCodec(key, validity, clock, serverId, true);
    }

    private CursorCodec(byte[] key, Duration validity, Clock clock, String serverId, boolean generatedKey) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("The cursor key must be at least 32 bytes");
        }
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("The cursor validity must be positive");
        }
        this.key = new SecretKeySpec(derive(key, Objects.requireNonNull(serverId, "serverId")), "AES");
        this.validity = validity;
        this.clock = clock;
        this.generatedKey = generatedKey;
    }

    /**
     * A cursor for the current caller; call it on the request thread of a tool.
     *
     * @param tool        name of the tool that will take the cursor back
     * @param fingerprint {@link #fingerprint(String...)} of the query without the cursor
     * @param state       the position in the backend's paging, at most {@value #MAX_STATE_BYTES} bytes of UTF-8
     */
    public String issue(String tool, String fingerprint, String state) {
        return issueFor(currentSubject(), tool, fingerprint, state);
    }

    /** As {@link #issue(String, String, String)} for an explicit {@code sub}. */
    public String issueFor(String subject, String tool, String fingerprint, String state) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (generatedKey && generatedKeyReported.compareAndSet(false, true)) {
            LOG.warn("aiwcorpo.mcp.platform.paging.cursor-key is not set: cursors are valid only on this instance until it restarts");
        }
        byte[] position = state.getBytes(StandardCharsets.UTF_8);
        if (position.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("The cursor position is longer than " + MAX_STATE_BYTES + " bytes");
        }
        long expires = clock.instant().plus(validity).getEpochSecond();
        byte[] plain = ByteBuffer.allocate(Long.BYTES + position.length).putLong(expires).put(position).array();
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] sealed = crypt(Cipher.ENCRYPT_MODE, nonce, subject, tool, fingerprint, plain);
        byte[] body = ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        String cursor = PREFIX + ENCODER.encodeToString(body);
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("The cursor is longer than " + MAX_CURSOR_LENGTH + " characters");
        }
        return cursor;
    }

    /**
     * The position inside a cursor the current caller got for this tool and query.
     *
     * @throws InvalidToolArgumentException with {@link #REFUSED_MESSAGE} for any cursor that is not valid
     */
    public String open(String cursor, String tool, String fingerprint) {
        return openFor(currentSubject(), cursor, tool, fingerprint);
    }

    /** As {@link #open(String, String, String)} for an explicit {@code sub}. */
    public String openFor(String subject, String cursor, String tool, String fingerprint) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(fingerprint, "fingerprint");
        try {
            if (cursor == null || cursor.length() > MAX_CURSOR_LENGTH) {
                throw refused();
            }
            if (!cursor.startsWith(PREFIX) || cursor.length() == PREFIX.length()) {
                throw refused();
            }
            String bodyText = cursor.substring(PREFIX.length());
            byte[] body = DECODER.decode(bodyText);
            // The decoder accepts padding and non-zero trailing bits, so one cursor would have several spellings.
            if (!ENCODER.encodeToString(body).equals(bodyText) || body.length < NONCE_BYTES + TAG_BITS / 8 + Long.BYTES) {
                throw refused();
            }
            byte[] nonce = java.util.Arrays.copyOfRange(body, 0, NONCE_BYTES);
            byte[] payload;
            try {
                payload = crypt(Cipher.DECRYPT_MODE, nonce, subject, tool, fingerprint,
                        java.util.Arrays.copyOfRange(body, NONCE_BYTES, body.length));
            } catch (IllegalStateException ex) {
                throw refused();
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            long expires = buffer.getLong();
            if (clock.instant().getEpochSecond() >= expires) {
                throw refused();
            }
            return new String(payload, Long.BYTES, payload.length - Long.BYTES, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            if (ex instanceof InvalidToolArgumentException refused) {
                throw refused;
            }
            throw refused();
        }
    }

    /**
     * A digest of the parts of a query that a cursor must stay tied to (search text, filters, sort, page size), without
     * the cursor itself. Parts are length-prefixed, so ("ab", "c") and ("a", "bc") differ; {@code null} differs from "".
     */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part == null) {
                    digest.update((byte) 0);
                    continue;
                }
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) 1);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return ENCODER.encodeToString(digest.digest());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * AES-256-GCM with the caller, the tool and the query as additional data. A failed tag is reported as
     * {@link IllegalStateException} on decryption (the caller turns it into the one refusal) and is a bug on encryption.
     */
    private byte[] crypt(int mode, byte[] nonce, String subject, String tool, String fingerprint, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(DOMAIN);
            for (String part : new String[] {subject, tool, fingerprint}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                cipher.updateAAD(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                cipher.updateAAD(bytes);
            }
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("cursor " + (mode == Cipher.ENCRYPT_MODE ? "encryption" : "decryption") + " failed");
        }
    }

    /** HKDF-SHA256 (RFC 5869, one output block): the cursor key of one server from the configured key. */
    private static byte[] derive(byte[] configured, String serverId) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(new byte[32], MAC_ALGORITHM));
            byte[] pseudoRandomKey = mac.doFinal(configured);
            mac.init(new SecretKeySpec(pseudoRandomKey, MAC_ALGORITHM));
            mac.update(DOMAIN);
            mac.update((byte) '|');
            mac.update(serverId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            return mac.doFinal();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }

    private static String currentSubject() {
        return CallerContext.current().map(CallerContext::subject)
                .orElseThrow(() -> new IllegalStateException("A cursor is issued and opened inside a request that passed the internal JWT"));
    }

    private static InvalidToolArgumentException refused() {
        return new InvalidToolArgumentException(REFUSED_MESSAGE);
    }
}
