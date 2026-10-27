package pl.aiwcorpo.mcp.platform;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** JSON Schemas of mcp-contracts from the classpath, for checking what the starter emits. */
public final class ContractSchemas {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static final Schema AUDIT_EVENT = schema("audit-event.schema.json");
    public static final Schema TOOL_ERROR = schema("tool-error.schema.json");

    private ContractSchemas() {
    }

    private static Schema schema(String file) {
        return REGISTRY.getSchema(SchemaLocation.of("classpath:mcp-contracts/schemas/" + file));
    }

    /** Violations of {@code json} against {@code schema}; empty when valid. */
    public static List<String> violations(Schema schema, String json) {
        List<Error> errors = schema.validate(JSON.readTree(json));
        return errors.stream().map(Error::toString).toList();
    }
}
