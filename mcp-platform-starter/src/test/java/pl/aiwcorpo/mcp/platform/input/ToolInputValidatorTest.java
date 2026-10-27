package pl.aiwcorpo.mcp.platform.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

class ToolInputValidatorTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "issueKey":{"type":"string","maxLength":10,"pattern":"^[A-Z]+-[0-9]+$"},
              "limit":{"type":"integer","minimum":1,"maximum":100},
              "state":{"type":"string","enum":["open","closed"]}},
             "required":["issueKey"],"additionalProperties":false}""";

    private static ToolDefinition tool(String schema) {
        return DefaultToolDefinition.builder().name("get_issue").description("d").inputSchema(schema).build();
    }

    private static ToolInputValidator strict() {
        return ToolInputValidator.compile(tool(SCHEMA), ToolInputPolicy.STRICT);
    }

    private static String messageOf(String input) {
        return assertThatThrownBy(() -> strict().validate(input)).isInstanceOf(InvalidToolArgumentException.class)
                .actual().getMessage();
    }

    @Test
    void validArgumentsPass() {
        strict().validate("{\"issueKey\":\"ABC-1\",\"limit\":5,\"state\":\"open\"}");
        strict().validate("{\"issueKey\":\"ABC-1\"}");
    }

    @Test
    void everyKindOfViolationIsReportedWithoutTheValue() {
        String secret = "LEAK-marker-9f3a";
        assertThat(messageOf("{\"issueKey\":\"" + secret + "\"}")).doesNotContain(secret)
                .contains("issueKey is longer than 10 characters");
        assertThat(messageOf("{\"issueKey\":\"abc-1\"}")).contains("issueKey does not match the required format ^[A-Z]+-[0-9]+$")
                .doesNotContain("abc-1");
        assertThat(messageOf("{\"issueKey\":\"A-1\",\"limit\":0}")).contains("limit is below the minimum 1");
        assertThat(messageOf("{\"issueKey\":\"A-1\",\"limit\":101}")).contains("limit is above the maximum 100");
        assertThat(messageOf("{\"issueKey\":\"A-1\",\"limit\":\"" + secret + "\"}")).contains("limit has the wrong type")
                .doesNotContain(secret);
        assertThat(messageOf("{\"issueKey\":\"A-1\",\"state\":\"" + secret + "\"}")).contains("state is not one of the allowed values")
                .doesNotContain(secret);
        assertThat(messageOf("{}")).contains("issueKey is required");
    }

    @Test
    void anUndeclaredArgumentIsRefusedAndItsNameIsNotEchoed() {
        String message = messageOf("{\"issueKey\":\"A-1\",\"leak_name_77\":\"x\"}");

        assertThat(message).contains("unknown arguments are not allowed").doesNotContain("leak_name_77");
    }

    @Test
    void brokenJsonAndNonObjectsAreInvalidArguments() {
        assertThat(messageOf("{\"issueKey\":")).contains("not a valid JSON object");
        assertThat(messageOf("[\"A-1\"]")).startsWith("Invalid arguments");
    }

    @Test
    void anOpenOrMissingSchemaStopsTheServerOutsideDev() {
        for (String schema : new String[] {"{}", "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"additionalProperties\":true}"}) {
            assertThatThrownBy(() -> ToolInputValidator.compile(tool(schema), ToolInputPolicy.STRICT))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("get_issue")
                    .hasMessageContaining("additionalProperties: false");
        }
    }

    @Test
    void inDevAnOpenSchemaStartsAndIsStillEnforcedAsFarAsItGoes() {
        ToolInputValidator open = ToolInputValidator.compile(
                tool("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}"), ToolInputPolicy.DEV);

        open.validate("{\"n\":1,\"anything\":true}");
        assertThatThrownBy(() -> open.validate("{\"n\":\"x\"}")).isInstanceOf(InvalidToolArgumentException.class);
        ToolInputValidator.compile(tool("{}"), ToolInputPolicy.DEV).validate("{\"a\":1}");
    }
}
