package pl.aiwcorpo.mcp.platform.policy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** The policy data endpoint of an adapter ({@code receiver-mode: adapter}), which has no engine: see {@link AdapterPolicyDataEndpoint}. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "aiwcorpo.mcp.platform.internal-jwt", name = "receiver-mode", havingValue = "adapter")
public class PlatformAdapterPolicyAutoConfiguration {

    @Bean
    AdapterPolicyDataEndpoint adapterPolicyDataEndpoint(org.springframework.beans.factory.ObjectProvider<pl.aiwcorpo.mcp.platform.auth.ConnectedMarker> connected) {
        return new AdapterPolicyDataEndpoint(connected.getIfAvailable());
    }
}
