package pl.aiwcorpo.mcp.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Tracing is off until MCP_OTLP_ENDPOINT is set, as a default of the lowest priority. */
class TracingDefaultsTest {

    private static MockEnvironment processed(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void tracingIsOffAndNothingIsExportedWhenNoEndpointIsGiven() {
        MockEnvironment environment = processed();
        assertThat(environment.getProperty("management.tracing.export.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.opentelemetry.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isNull();
    }

    @Test
    void aBlankEndpointKeepsTracingOff() {
        assertThat(processed("MCP_OTLP_ENDPOINT", " ").getProperty("management.tracing.export.enabled")).isEqualTo("false");
    }

    @Test
    void anEndpointTurnsTracingOnAndIsPassedToTheExporter() {
        MockEnvironment environment = processed("MCP_OTLP_ENDPOINT", "http://collector:4318/v1/traces");
        assertThat(environment.getProperty("management.tracing.export.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.opentelemetry.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://collector:4318/v1/traces");
    }

    @Test
    void baggageLogExportAndTheSpansOfRedisAndScheduledTasksStayOff() {
        MockEnvironment environment = processed("MCP_OTLP_ENDPOINT", "http://collector:4318/v1/traces");
        assertThat(environment.getProperty("management.tracing.baggage.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.logging.export.otlp.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.observations.enable.lettuce")).isEqualTo("false");
        assertThat(environment.getProperty("management.observations.enable.tasks")).isEqualTo("false");
    }
}
