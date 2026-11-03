package pl.aiwcorpo.mcp.platform.paging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

/**
 * The cursor a paged tool hands to the client and takes back: an opaque, signed token that carries the position in
 * the backend's own paging (an offset, a {@code nextPageToken}, a keyset value), so a server does not invent its own
 * cursor format and does not trust one it gets from the model.
 *
 * <p>A cursor is bound to the caller ({@code sub}), the tool, and the query it was issued for (see
 * {@link #fingerprint(String...)}), and expires. A cursor of another user, of another tool, of a changed query, a
 * forged, truncated or expired one is refused with the same fixed {@code invalid_argument} text, so the text tells
 * nothing about the cause. The signature is HMAC-SHA256 over the binding and the payload.
 *
 * <p>What a cursor is not: a grant. It never carries the scope of the caller. Every page is fetched with the scope
 * computed again for the current request ({@code QueryScope}) and filtered again by the {@code ResultFilter}, so a
 * forged or replayed cursor cannot widen what a page contains. The payload is readable by the client (base64url, not
 * encrypted): put only the backend's position in it, never a credential or a value the client must not see.
 *
 * <p>Instances of one server must share {@code aiwcorpo.mcp.platform.paging.cursor-key}; with a key generated at
 * startup a cursor issued by one instance is refused by another and by the same one after a restart. The gateway's
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
    private static final byte[] DOMAIN = "mcp-platform-cursor-v1".getBytes(StandardCharsets.US_ASCII);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Duration validity;
    private final Clock clock;

    public CursorCodec(byte[] key, Duration validity, Clock clock) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("The cursor key must be at least 32 bytes");
        }
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("The cursor validity must be positive");
        }
        this.key = new SecretKeySpec(key.clone(), MAC_ALGORITHM);
        this.validity = validity;
        this.clock = clock;
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
        byte[] position = state.getBytes(StandardCharsets.UTF_8);
        if (position.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("The cursor position is longer than " + MAX_STATE_BYTES + " bytes");
        }
        long expires = clock.instant().plus(validity).getEpochSecond();
        byte[] payload = ByteBuffer.allocate(Long.BYTES + position.length).putLong(expires).put(position).array();
        String cursor = ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(subject, tool, fingerprint, payload));
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
        try {
            if (cursor == null || cursor.length() > MAX_CURSOR_LENGTH) {
                throw refused();
            }
            int dot = cursor.indexOf('.');
            if (dot <= 0 || dot != cursor.lastIndexOf('.') || dot == cursor.length() - 1) {
                throw refused();
            }
            byte[] payload = DECODER.decode(cursor.substring(0, dot));
            byte[] signature = DECODER.decode(cursor.substring(dot + 1));
            if (payload.length < Long.BYTES
                    || !MessageDigest.isEqual(signature, sign(subject, tool, fingerprint, payload))) {
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

    private byte[] sign(String subject, String tool, String fingerprint, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            mac.update(DOMAIN);
            for (String part : new String[] {subject, tool, fingerprint}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                mac.update(bytes);
            }
            mac.update(payload);
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
