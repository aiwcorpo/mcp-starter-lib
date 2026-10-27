package pl.aiwcorpo.mcp.platform.observability;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/** Request ids as the contracts define them ({@code RequestId}: a lowercase UUIDv7). */
public final class RequestIds {

    /** {@code common.schema.json#/$defs/RequestId}. */
    public static final Pattern PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private RequestIds() {
    }

    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /** A new UUIDv7 (RFC 9562): 48-bit Unix milliseconds, version 7, variant 10, 74 random bits. */
    public static String newRequestId() {
        long millis = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long randA = ((random[0] & 0x0fL) << 8) | (random[1] & 0xffL);
        long mostSignificant = (millis << 16) | 0x7000L | randA;
        long randB = 0;
        for (int i = 2; i < 10; i++) {
            randB = (randB << 8) | (random[i] & 0xffL);
        }
        long leastSignificant = (randB & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant).toString();
    }
}
