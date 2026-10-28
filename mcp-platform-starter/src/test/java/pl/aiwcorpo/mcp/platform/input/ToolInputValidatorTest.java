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
    void aNestedViolationIsNamedByPathAndLimitWithoutTheValue() {
        String schema = """
                {"type":"object","additionalProperties":false,"required":["jql_filter"],"properties":{
                  "jql_filter":{"type":"object","additionalProperties":false,"required":["status"],"properties":{
                    "status":{"type":"string","maxLength":40},
                    "labels":{"type":"array","maxItems":3,"items":{"type":"string","maxLength":8}}}}}}
                """;
        ToolInputValidator validator = ToolInputValidator.compile(tool(schema), ToolInputPolicy.STRICT);
        String secret = "LEAK-marker-9f3a";

        String tooLong = assertThatThrownBy(() -> validator.validate("{\"jql_filter\":{\"status\":\"" + secret.repeat(4) + "\"}}"))
                .actual().getMessage();
        String item = assertThatThrownBy(() -> validator.validate("{\"jql_filter\":{\"status\":\"a\",\"labels\":[\"ok\",\"" + secret + "\"]}}"))
                .actual().getMessage();
        String missing = assertThatThrownBy(() -> validator.validate("{\"jql_filter\":{}}")).actual().getMessage();

        assertThat(tooLong).contains("jql_filter.status is longer than 40 characters").doesNotContain(secret);
        assertThat(item).contains("jql_filter.labels[1] is longer than 8 characters").doesNotContain(secret);
        assertThat(missing).contains("jql_filter.status is required");
    }

    @Test
    void aKeyOfAMapFromTheInputIsNotInTheMessage() {
        String schema = """
                {"type":"object","additionalProperties":false,"required":["issueKey"],"properties":{
                  "issueKey":{"type":"string","maxLength":20},
                  "labels":{"type":"object","maxProperties":3,"propertyNames":{"maxLength":20},"additionalProperties":{"type":"string","maxLength":5}}}}
                """;
        ToolInputValidator validator = ToolInputValidator.compile(tool(schema), ToolInputPolicy.STRICT);

        String message = assertThatThrownBy(() -> validator.validate("{\"issueKey\":\"A-1\",\"labels\":{\"SECRETKEY77\":\"too long value\"}}"))
                .actual().getMessage();

        assertThat(message).contains("labels.* is longer than 5 characters").doesNotContain("SECRETKEY77");
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

    @Test
    void aTrailingLineBreakOrBlankDoesNotSatisfyAnAnchoredPattern() {
        for (String value : new String[] {"ABC-1\\n", "ABC-1\\r\\n", "ABC-1 ", "ABC-1\\r", "\\nABC-1", "ABC-1\\u2028"}) {
            assertThat(messageOf("{\"issueKey\":\"" + value + "\"}")).as(value)
                    .contains("issueKey does not match the required format");
        }
        strict().validate("{\"issueKey\":\"ABC-1\"}");
    }

    @Test
    void dollarIsRewrittenOnlyWhereItMeansEndOfInput() {
        assertThat(ToolInputValidator.anchorEnd("^[A-Z]+$")).isEqualTo("^[A-Z]+\\z");
        assertThat(ToolInputValidator.anchorEnd("^a\\\\$")).isEqualTo("^a\\\\\\z");
        assertThat(ToolInputValidator.anchorEnd("^[$]+\\$x$")).isEqualTo("^[$]+\\$x\\z");
        assertThat(ToolInputValidator.anchorEnd("^a$|^b$")).isEqualTo("^a\\z|^b\\z");
    }

    @Test
    void controlCharactersAreRefusedInTextAndLineBreaksOnlyWhereThereIsNoFormat() {
        ToolInputValidator free = ToolInputValidator.compile(tool(
                "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":100},\"key\":{\"type\":\"string\",\"maxLength\":100,"
                        + "\"pattern\":\"^[\\\\s\\\\S]+$\"}},\"additionalProperties\":false}"), ToolInputPolicy.STRICT);

        free.validate("{\"body\":\"line one\\nline two\\ttabbed\"}");
        free.validate("{\"key\":\"a\\nb\"}");
        assertThatThrownBy(() -> free.validate("{\"body\":\"red \\u001b[31m\"}"))
                .hasMessageContaining("body contains control characters");
        assertThatThrownBy(() -> free.validate("{\"body\":\"nul\\u0000\"}")).isInstanceOf(InvalidToolArgumentException.class);
        assertThatThrownBy(() -> free.validate("{\"body\":\"c1\\u0085\"}")).isInstanceOf(InvalidToolArgumentException.class);
        assertThatThrownBy(() -> strict().validate("{\"issueKey\":\"AB\\u0007\"}")).isInstanceOf(InvalidToolArgumentException.class);
    }
}
