package pl.aiwcorpo.mcp.platform.capability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/** Runs the server's {@link CapabilityProbe} beans, when it declares any; without one nothing here is created. */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformCapabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    BackendCapabilities backendCapabilities() {
        return new BackendCapabilities();
    }

    @Bean
    @ConditionalOnBean(CapabilityProbe.class)
    CapabilityProbeRunner capabilityProbeRunner(List<CapabilityProbe> probes, BackendCapabilities capabilities,
                                                ObjectProvider<MeterRegistry> meters, McpPlatformProperties props) {
        McpPlatformProperties.Capability settings = props.getCapability();
        return new CapabilityProbeRunner(probes, capabilities, meters.getIfAvailable(), settings.getTimeout(),
                settings.getInterval(), settings.getRetryInterval());
    }

    @Bean(name = "backendCapabilityHealthIndicator")
    @ConditionalOnBean(CapabilityProbe.class)
    BackendCapabilityHealthIndicator backendCapabilityHealthIndicator(BackendCapabilities capabilities) {
        return new BackendCapabilityHealthIndicator(capabilities);
    }
}
