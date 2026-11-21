package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UriLabelTest {

    @Test
    void aTemplateKeepsItsPathAndLosesItsQueryVariablesAndAnythingElseBecomesUnknown() {
        assertThat(UriLabel.of("/users/{id}/issues?state={state}&page={page}")).isEqualTo("/users/{id}/issues");
        assertThat(UriLabel.of("https://{user}:{password}@backend/items/{id}")).isEqualTo("https://backend/items/{id}");
        assertThat(UriLabel.of("/items?q=literal")).isEqualTo(UriLabel.UNKNOWN);
        assertThat(UriLabel.of("https://user:secret@backend/items")).isEqualTo(UriLabel.UNKNOWN);
        assertThat(UriLabel.of("/items#section")).isEqualTo(UriLabel.UNKNOWN);
        assertThat(UriLabel.of("none")).isEqualTo("none");
        assertThat(UriLabel.of("/s/{serverId}/mcp")).isEqualTo("/s/{serverId}/mcp");
    }
}
