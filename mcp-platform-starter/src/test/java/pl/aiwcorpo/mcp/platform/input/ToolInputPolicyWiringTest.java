package pl.aiwcorpo.mcp.platform.input;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.aiwcorpo.mcp.platform.observability.PlatformObservabilityAutoConfiguration;

class ToolInputPolicyWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformObservabilityAutoConfiguration.class))
            .withPropertyValues("aiwcorpo.mcp.platform.server-id=mcp-jira");

    @Test
    void onlyTheOneAndOnlyDevProfileMayRunOpenSchemas() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context.getBean(ToolInputPolicy.class)).isEqualTo(ToolInputPolicy.DEV));
        runner.withPropertyValues("spring.profiles.active=dev,prod")
                .run(context -> assertThat(context.getBean(ToolInputPolicy.class)).isEqualTo(ToolInputPolicy.STRICT));
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context.getBean(ToolInputPolicy.class)).isEqualTo(ToolInputPolicy.STRICT));
        runner.run(context -> assertThat(context.getBean(ToolInputPolicy.class)).isEqualTo(ToolInputPolicy.STRICT));
    }
}
