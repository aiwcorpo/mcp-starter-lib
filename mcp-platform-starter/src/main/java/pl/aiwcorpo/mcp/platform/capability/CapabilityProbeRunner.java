package pl.aiwcorpo.mcp.platform.capability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Runs the {@link CapabilityProbe}s after the application started and then every
 * {@code aiwcorpo.mcp.platform.capability.interval} (every {@code retry-interval} while a backend is unavailable), on
 * threads of its own so a slow system never delays startup or a request. A change of state is one log line; the
 * metric {@code mcp_backend_capability{probe,state}} is 1 for the current state and 0 for the other two.
 */
public final class CapabilityProbeRunner implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(CapabilityProbeRunner.class);

    private final List<CapabilityProbe> probes;
    private final BackendCapabilities capabilities;
    private final Duration timeout;
    private final Duration interval;
    private final Duration retryInterval;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    public CapabilityProbeRunner(List<CapabilityProbe> probes, BackendCapabilities capabilities, MeterRegistry meters,
                                 Duration timeout, Duration interval, Duration retryInterval) {
        this.probes = List.copyOf(probes);
        this.capabilities = capabilities;
        this.timeout = timeout;
        this.interval = interval;
        this.retryInterval = retryInterval;
        long distinct = probes.stream().map(CapabilityProbe::name).distinct().count();
        if (distinct != probes.size()) {
            throw new IllegalStateException("Two capability probes have the same name");
        }
        // One thread per probe on both pools: a probe that ignores interruption occupies its own thread and no other.
        int threads = Math.max(1, probes.size());
        this.scheduler = Executors.newScheduledThreadPool(threads,
                Thread.ofPlatform().name("capability-probe-scheduler-", 0).daemon().factory());
        this.workers = Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("capability-probe-", 0).daemon().factory());
        if (meters != null) {
            for (CapabilityProbe probe : probes) {
                for (Capabilities.State state : Capabilities.State.values()) {
                    Gauge.builder("mcp.backend.capability", capabilities,
                                    c -> c.of(probe.name()).state() == state ? 1 : 0)
                            .tag("probe", probe.name()).tag("state", state.name().toLowerCase(Locale.ROOT))
                            .register(meters);
                }
            }
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    /**
     * Schedules the first run of every probe; each probe has a scheduler thread of its own, so a slow one does not delay
     * another. Calling it again does nothing (a parent and a child context both publish the event). Public so a test can
     * start the runner without an application.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (CapabilityProbe probe : probes) {
            scheduler.execute(() -> runAndReschedule(probe));
        }
    }

    private void runAndReschedule(CapabilityProbe probe) {
        Capabilities result = runOnce(probe);
        if (!scheduler.isShutdown()) {
            Duration next = result.state() == Capabilities.State.UNAVAILABLE ? retryInterval : interval;
            scheduler.schedule(() -> runAndReschedule(probe), next.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** One probe now, on a worker thread with the timeout; the answer is stored and returned. */
    Capabilities runOnce(CapabilityProbe probe) {
        AtomicBoolean busy = running.computeIfAbsent(probe.name(), name -> new AtomicBoolean());
        // The flag is cleared by the worker itself: a cancelled future is "done" while its thread may still be stuck.
        if (!busy.compareAndSet(false, true)) {
            return store(probe, Capabilities.unavailable("the previous probe is still running"));
        }
        Future<Capabilities> pending = workers.submit(() -> {
            try {
                return probe.probe();
            } finally {
                busy.set(false);
            }
        });
        Capabilities result;
        try {
            result = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) {
                result = Capabilities.unavailable("the probe returned nothing");
            }
        } catch (TimeoutException ex) {
            pending.cancel(true);
            result = Capabilities.unavailable("no answer within " + timeout.toMillis() + " ms");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            result = Capabilities.unavailable("interrupted");
        } catch (ExecutionException ex) {
            // Only the class: a message from an HTTP client may carry a URL with a token or a response body.
            result = Capabilities.unavailable("probe failed with " + ex.getCause().getClass().getSimpleName());
        }
        return store(probe, result);
    }

    private Capabilities store(CapabilityProbe probe, Capabilities result) {
        Capabilities before = capabilities.of(probe.name());
        capabilities.update(probe.name(), result);
        if (before.state() != result.state() || !Objects.equals(before.backendVersion(), result.backendVersion())) {
            report(probe.name(), result);
        }
        return result;
    }

    private static void report(String name, Capabilities result) {
        switch (result.state()) {
            case SUPPORTED -> LOG.info("backend capability: probe={} state=supported version={} features={}",
                    name, result.backendVersion(), result.features().size());
            case DEGRADED -> LOG.warn("backend capability: probe={} state=degraded version={} baselineFeatures={} detail={}",
                    name, result.backendVersion(), result.features().size(), result.detail());
            case UNAVAILABLE -> LOG.warn("backend capability: probe={} state=unavailable detail={}", name, result.detail());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
