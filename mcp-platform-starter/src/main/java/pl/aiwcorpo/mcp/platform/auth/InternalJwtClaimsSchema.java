package pl.aiwcorpo.mcp.platform.auth;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * {@code schemas/internal-jwt.schema.json} from the mcp-contracts jar on the classpath: required claims per
 * variant, no unknown claims (a token with {@code vs} or {@code vsc} is {@code invalid_claims}), identifier patterns,
 * {@code sub} of the user variant not starting with {@code system:}. The verifier checks against the
 * contract itself rather than a hand-made copy of its rules.
 */
final class InternalJwtClaimsSchema {

    static final String LOCATION = "classpath:mcp-contracts/schemas/internal-jwt.schema.json";

    private final Schema schema;

    InternalJwtClaimsSchema() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.schema = registry.getSchema(SchemaLocation.of(LOCATION));
        // Resolve every $ref now: a missing common.schema.json must stop the server, not fail a request.
        this.schema.initializeValidators();
    }

    /** Violations as {@code keyword@instancePath}; never the offending values, which come from the token. */
    List<String> violations(JsonNode claims) {
        List<Error> errors = schema.validate(claims);
        return errors.stream().map(e -> e.getKeyword() + "@" + e.getInstanceLocation()).toList();
    }
}
