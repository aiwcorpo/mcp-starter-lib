package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;

/** S-L3: a server bean cannot switch the bounded-schema rule or the audit wrapper off quietly. */
class ForeignToolPolicyCheckTest {

    @Configuration
    static class OpenPolicy {
        @Bean
        ToolInputPolicy relaxedPolicy() {
            return ToolInputPolicy.DEV;
        }
    }

    @Configuration
    static class OwnWrapper {
        @Bean
        static ToolCallbackAuditPostProcessor ownWrapper() {
            ObjectProvider<AuditLogger> none = null;
            return new ToolCallbackAuditPostProcessor(none, null, null, null);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformObservabilityAutoConfiguration.class))
            .withPropertyValues("aiwcorpo.mcp.platform.server-id=mcp-jira");

    @Test
    void aServerPolicyBeanStopsTheServerOutsideDev() {
        runner.withUserConfiguration(OpenPolicy.class).run(context -> assertThat(context).hasFailed()
                .getFailure().hasMessageContaining("relaxedPolicy"));
        runner.withUserConfiguration(OpenPolicy.class).withPropertyValues("spring.profiles.active=dev,prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aServerWrapperBeanStopsTheServerOutsideDev() {
        runner.withUserConfiguration(OwnWrapper.class).run(context -> assertThat(context).hasFailed()
                .getFailure().hasMessageContaining("ownWrapper"));
    }

    @Test
    void inDevAServerBeanIsLeftAlone() {
        runner.withUserConfiguration(OpenPolicy.class).withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void withoutServerBeansThePlatformPolicyIsInPlace() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolInputPolicy.class)).isEqualTo(ToolInputPolicy.STRICT);
        });
    }
}
