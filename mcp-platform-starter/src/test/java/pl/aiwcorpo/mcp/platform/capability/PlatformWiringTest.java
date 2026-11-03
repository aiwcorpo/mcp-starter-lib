package pl.aiwcorpo.mcp.platform.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.aiwcorpo.mcp.platform.paging.CursorCodec;
import pl.aiwcorpo.mcp.platform.paging.PlatformPagingAutoConfiguration;

class PlatformWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformCapabilityAutoConfiguration.class,
                    PlatformPagingAutoConfiguration.class));

    @Test
    void withoutAProbeNothingRunsAndTheStoreIsStillThereForTools() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BackendCapabilities.class);
            assertThat(context).doesNotHaveBean(CapabilityProbeRunner.class);
            assertThat(context).doesNotHaveBean("backendCapabilityHealthIndicator");
        });
    }

    @Test
    void aProbeBeanStartsTheRunnerAndTheHealthContributor() {
        runner.withBean(CapabilityProbe.class, () -> new CapabilityProbe() {
            @Override
            public String name() {
                return "jira";
            }

            @Override
            public Capabilities probe() {
                return Capabilities.supported("9.12.0", Set.of("search"));
            }
        }).run(context -> {
            assertThat(context).hasSingleBean(CapabilityProbeRunner.class);
            assertThat(context).hasBean("backendCapabilityHealthIndicator");
        });
    }

    @Test
    void theCursorCodecTakesTheConfiguredKeyOrCreatesOne() {
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        runner.withPropertyValues("aiwcorpo.mcp.platform.paging.cursor-key=" + key)
                .run(context -> assertThat(context).hasSingleBean(CursorCodec.class));
        runner.run(context -> assertThat(context).hasSingleBean(CursorCodec.class));
    }

    @Test
    void aShortOrMalformedKeyStopsTheServer() {
        runner.withPropertyValues("aiwcorpo.mcp.platform.paging.cursor-key=" + Base64.getEncoder().encodeToString(new byte[8]))
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("aiwcorpo.mcp.platform.paging.cursor-key=not base64 !!")
                .run(context -> assertThat(context).hasFailed());
    }
}
