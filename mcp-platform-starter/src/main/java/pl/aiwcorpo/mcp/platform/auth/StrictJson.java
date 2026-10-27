package pl.aiwcorpo.mcp.platform.auth;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON parsing for security decisions. Duplicate keys and trailing content are errors: with them, two
 * parsers can read different values from the same bytes ({@code {"tool":"a","tool":"b"}}), and the one
 * that checked would not be the one that acted.
 */
final class StrictJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private StrictJson() {
    }

    /** The parsed document, or {@code null} when the bytes are not exactly one well-formed JSON value. */
    static JsonNode parse(byte[] json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException ex) {
            return null;
        }
    }
}
