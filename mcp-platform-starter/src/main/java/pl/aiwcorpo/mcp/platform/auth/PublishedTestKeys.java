package pl.aiwcorpo.mcp.platform.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The keys published in {@code mcp-contracts/test-vectors/keys}. Their private halves are public, so a key
 * with one of these encodings is a test key whatever {@code kid} the JWKS gives it (CC-044).
 *
 * <p>The list is {@code mcp-contracts/security/published-test-keys.json} from the mcp-contracts jar (2.0.1+),
 * the same source {@code PublishedTestKeys} of mcp-cedar-entities is tested against; reading the file keeps
 * cedar-java out of this module until the policy engine needs it.
 */
final class PublishedTestKeys {

    static final String RESOURCE = "/mcp-contracts/security/published-test-keys.json";

    static final Set<String> SPKI_SHA256 = load();

    private PublishedTestKeys() {
    }

    static boolean isTestKey(String kid, ECKey key) {
        return kid.startsWith("test-") || SPKI_SHA256.contains(spkiSha256(key));
    }

    static String spkiSha256(ECKey key) {
        try {
            byte[] spki = key.toECPublicKey().getEncoded();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spki));
        } catch (JOSEException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("cannot encode the gateway key", ex);
        }
    }

    private static Set<String> load() {
        try (InputStream in = PublishedTestKeys.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing: mcp-contracts 2.0.1 or newer must be on the classpath");
            }
            Set<String> digests = new HashSet<>();
            for (JsonNode key : JsonMapper.builder().build().readTree(in).required("keys")) {
                digests.add(key.required("spkiSha256").asString());
            }
            if (digests.isEmpty()) {
                throw new IllegalStateException(RESOURCE + " lists no keys");
            }
            return Set.copyOf(digests);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + RESOURCE, ex);
        }
    }
}
