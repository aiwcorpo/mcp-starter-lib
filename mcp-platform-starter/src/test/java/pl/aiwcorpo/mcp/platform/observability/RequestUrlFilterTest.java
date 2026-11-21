package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

class RequestUrlFilterTest {

    @Test
    void theFullUrlWithItsQueryIsDroppedAndTheTemplateStays() {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(KeyValue.of("http.url", "https://backend/rest/api?jql=assignee%3Dsecret"));
        context.addLowCardinalityKeyValue(KeyValue.of("uri", "/rest/api"));

        new RequestUrlFilter().map(context);

        assertThat(context.getHighCardinalityKeyValues().stream().map(KeyValue::getKey)).doesNotContain("http.url");
        assertThat(context.getLowCardinalityKeyValues().stream().map(KeyValue::getKey)).contains("uri");
    }
}
