package pl.aiwcorpo.mcp.platform.capability;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * {@code backendCapability} in the health endpoint. The statuses {@code DEGRADED} and {@code UNAVAILABLE} are not in
 * Spring's aggregation order on purpose: they show in the details but never turn the application's own status to DOWN,
 * because a backend that is down or unknown is not a reason to take the server out of the balancer (it still answers
 * every call with {@code upstream_unavailable}, and the other servers' tools keep working).
 */
public final class BackendCapabilityHealthIndicator implements HealthIndicator {

    private final BackendCapabilities capabilities;

    public BackendCapabilityHealthIndicator(BackendCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public Health health() {
        var all = new TreeMap<>(capabilities.all());
        Status worst = Status.UP;
        Health.Builder health = Health.up();
        for (var entry : all.entrySet()) {
            Capabilities c = entry.getValue();
            health.withDetail(entry.getKey(), new TreeMap<>(Map.of(
                    "state", c.state().name().toLowerCase(Locale.ROOT),
                    "version", c.backendVersion() == null ? "" : c.backendVersion(),
                    "features", new TreeSet<>(c.features()),
                    "detail", c.detail())));
            if (c.state() == Capabilities.State.UNAVAILABLE) {
                worst = new Status("UNAVAILABLE");
            } else if (c.isDegraded() && !worst.getCode().equals("UNAVAILABLE")) {
                worst = new Status("DEGRADED");
            }
        }
        return health.status(worst).build();
    }
}
