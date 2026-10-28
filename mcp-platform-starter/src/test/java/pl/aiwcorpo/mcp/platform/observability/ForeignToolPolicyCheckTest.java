package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import pl.aiwcorpo.mcp.platform.auth.InternalJwtFilter;
import pl.aiwcorpo.mcp.platform.auth.InternalJwtVerifier;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.error.PlatformErrorAutoConfiguration;
import pl.aiwcorpo.mcp.platform.guard.RegisteredToolsCheck;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolHintsPostProcessor;
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

    @Configuration
    static class DisguisedPolicy {
        @Bean
        @Primary
        Object relaxed() {
            return ToolInputPolicy.DEV;
        }
    }

    @Configuration
    static class OwnChecks {
        @Bean
        RegisteredToolsCheck ownCheck() {
            return new RegisteredToolsCheck(null, null);
        }
    }

    @Configuration
    static class OwnHints {
        @Bean
        static ToolHintsPostProcessor ownHints() {
            return new ToolHintsPostProcessor(null);
        }
    }

    @Configuration
    static class OwnGuard {
        @Bean
        PlatformConfigurationGuard ownGuard() {
            return null;
        }
    }

    @Configuration
    static class OwnJwtCheck {
        @Bean
        InternalJwtVerifier ownVerifier() {
            return null;
        }

        @Bean
        InternalJwtFilter ownFilter() {
            return null;
        }
    }

    @Configuration
    static class SwappedDefinition {
        @Bean
        static org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor swap() {
            return new org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(
                        org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
                    registry.removeBeanDefinition("toolCallbackAuditPostProcessor");
                    registry.registerBeanDefinition("toolCallbackAuditPostProcessor",
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    ToolCallbackAuditPostProcessor.class, () -> null));
                }
            };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformObservabilityAutoConfiguration.class,
                    PlatformErrorAutoConfiguration.class))
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

    @Test
    void aDefinitionSwappedUnderThePlatformsBeanNameStopsTheServer() {
        runner.withUserConfiguration(SwappedDefinition.class).run(context -> assertThat(context).hasFailed()
                .getFailure().hasMessageContaining("definition replaced"));
    }

    @Test
    void theResultSizeLimitMustBePositive() {
        runner.withPropertyValues("aiwcorpo.mcp.platform.tools.max-result-size=0B").run(context -> assertThat(context)
                .hasFailed().getFailure().hasMessageContaining("max-result-size must be positive"));
    }

    @Test
    void aServerCannotConstructTheWrapperOrItsPostProcessor() {
        for (Class<?> type : new Class<?>[] {AuditingToolCallback.class, ToolCallbackAuditPostProcessor.class}) {
            assertThat(java.lang.reflect.Modifier.isFinal(type.getModifiers())).as(type.getSimpleName()).isTrue();
            assertThat(type.getConstructors()).as(type.getSimpleName()).isEmpty();
        }
        assertThat(ToolInputPolicy.class.isEnum()).isTrue();
    }

    @Test
    void aServerCheckOrHintsBeanStopsTheServerOutsideDev() {
        runner.withUserConfiguration(OwnChecks.class).run(context -> assertThat(context).hasFailed()
                .getFailure().hasMessageContaining("ownCheck"));
        runner.withUserConfiguration(OwnHints.class).run(context -> assertThat(context).hasFailed()
                .getFailure().hasMessageContaining("ownHints"));
    }

    @Test
    void theConfigurationGuardAndTheJwtCheckCannotBeReplacedInAnyProfile() {
        for (String profile : new String[] {"prod", "dev"}) {
            runner.withUserConfiguration(OwnGuard.class).withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining("ownGuard"));
            runner.withUserConfiguration(OwnJwtCheck.class).withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasMessageContaining("ownVerifier").hasMessageContaining("ownFilter"));
        }
    }

    @Test
    void aPolicyBeanTheCheckCannotSeeStillDoesNotRelaxTheWrapper() {
        runner.withUserConfiguration(DisguisedPolicy.class).run(context -> {
            assertThat(context).hasNotFailed();
            ToolCallbackAuditPostProcessor wrapper = context.getBean(ToolCallbackAuditPostProcessor.class);
            assertThatThrownBy(() -> wrapper.decorate(new OpenTool()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("open_tool");
        });
    }

    private record OpenTool() implements ToolCallback, RiskClassifiedTool {

        @Override
        public String title() {
            return "Test tool";
        }

        @Override
        public boolean resultWithoutResources() {
            return true;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name("open_tool").description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}").build();
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.READ_ONLY;
        }
    }
}
