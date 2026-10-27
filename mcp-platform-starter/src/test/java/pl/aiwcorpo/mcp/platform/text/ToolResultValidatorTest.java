package pl.aiwcorpo.mcp.platform.text;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.lang.Nullable;
import tools.jackson.databind.json.JsonMapper;

class ToolResultValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Ticket(String key, @Nullable String assignee, UntrustedText summary) {
    }

    static class Tools {
        Ticket ticket() {
            return null;
        }
    }

    private static ToolResultValidator validator() throws Exception {
        Method method = Tools.class.getDeclaredMethod("ticket");
        return ToolResultValidator.compile("read_ticket", ToolOutputSchemas.forMethod(method).orElseThrow());
    }

    private static String json(Ticket ticket) {
        return JSON.writeValueAsString(ticket);
    }

    @Test
    void whatTheTypeSerializesToPasses() throws Exception {
        UntrustedText summary = UntrustedText.plain("s", 10);

        assertThatCode(() -> validator().validate(json(new Ticket("T-1", "jk", summary)))).doesNotThrowAnyException();
        assertThatCode(() -> validator().validate(json(new Ticket("T-1", null, summary)))).doesNotThrowAnyException();
    }

    @Test
    void aNullWhereTheComponentIsNotMarkedNullableIsNamedByPathAndNeverQuoted() throws Exception {
        String result = "{\"key\":null,\"assignee\":null,\"summary\":{\"kind\":\"untrusted-data\",\"format\":\"text/plain\","
                + "\"text\":\"secret-system-text\",\"truncated\":false}}";

        assertThatThrownBy(() -> validator().validate(result)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read_ticket").hasMessageContaining("/key (type)")
                .hasMessageContaining("@Nullable").message().doesNotContain("secret-system-text");
    }

    @Test
    void aRenamedOrMissingFieldAndATextThatIsNotJsonAreRefused() throws Exception {
        String summary = "{\"kind\":\"untrusted-data\",\"format\":\"text/plain\",\"text\":\"s\",\"truncated\":false}";

        assertThatThrownBy(() -> validator().validate("{\"issue_key\":\"T-1\",\"assignee\":null,\"summary\":" + summary + "}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validator().validate("{\"assignee\":null,\"summary\":" + summary + "}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validator().validate("plain text")).isInstanceOf(IllegalStateException.class)
                .message().doesNotContain("plain text");
    }
}
