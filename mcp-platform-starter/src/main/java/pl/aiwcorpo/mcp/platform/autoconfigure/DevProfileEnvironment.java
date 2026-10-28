package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.core.env.Environment;

/**
 * Recognizes a runtime where the {@code dev} profile can only be a mistake. The profile takes TLS, mTLS and Redis
 * off the server, so a stray {@code SPRING_PROFILES_ACTIVE=dev} copied into a deployment would leave a production
 * server with the gateway's internal JWT as its only protection. A deployment is recognized by what a developer's
 * terminal does not have: a cloud platform Spring Boot detects (Kubernetes, Cloud Foundry, ...), the Kubernetes
 * service variables, a service manager's invocation id (the systemd unit of a real installation) or a container
 * (Docker, Podman: marker files, the {@code container} variable, the cgroup path).
 *
 * <p>This is a safety net against copying configuration, not a security boundary: whoever controls the process
 * environment can hide every one of these signals. The real protection is that the production profiles refuse
 * the relaxations at all.
 *
 * <p>A context built by Spring's test framework is not a deployment, whatever the host looks like: server tests
 * with {@code TestGateway} need the {@code dev} profile and run in CI on Kubernetes pods and under systemd
 * runners. The test context is recognized by the test framework on the call stack that builds the context, not by a
 * property, because a property could be set in a deployment as well.
 */
final class DevProfileEnvironment {

    private static final Pattern CONTAINER_CGROUP = Pattern.compile("/(docker|kubepods|libpod|crio|containerd)[-/.]");

    private DevProfileEnvironment() {
    }

    /** What the runtime looks like from the outside: process variables and files that a system property cannot hide. */
    interface Host {

        String variable(String name);

        boolean exists(String path);

        String read(String path);
    }

    static final Host LOCAL_HOST = new Host() {
        @Override
        public String variable(String name) {
            return System.getenv(name);
        }

        @Override
        public boolean exists(String path) {
            return Files.exists(Path.of(path));
        }

        @Override
        public String read(String path) {
            try {
                return Files.readString(Path.of(path));
            } catch (IOException | RuntimeException ex) {
                return "";
            }
        }
    };

    /** What marks the runtime as a deployment, or empty on a developer's machine or in a test context. */
    static Optional<String> deploymentSignal(Environment environment) {
        return deploymentSignal(environment, LOCAL_HOST, DevProfileEnvironment::inSpringTestContext);
    }

    static Optional<String> deploymentSignal(Environment environment, Host host,
            BooleanSupplier testContext) {
        if (testContext.getAsBoolean()) {
            return Optional.empty();
        }
        CloudPlatform platform = CloudPlatform.getActive(environment);
        if (platform != null && platform != CloudPlatform.NONE) {
            return Optional.of("cloud platform " + platform);
        }
        if (set(environment.getProperty("KUBERNETES_SERVICE_HOST")) && set(environment.getProperty("KUBERNETES_SERVICE_PORT"))
                || set(host.variable("KUBERNETES_SERVICE_HOST")) && set(host.variable("KUBERNETES_SERVICE_PORT"))) {
            return Optional.of("a Kubernetes pod (KUBERNETES_SERVICE_HOST and KUBERNETES_SERVICE_PORT are set)");
        }
        if (set(environment.getProperty("INVOCATION_ID")) || set(host.variable("INVOCATION_ID"))) {
            return Optional.of("a systemd service (INVOCATION_ID is set)");
        }
        if (host.exists("/.dockerenv") || host.exists("/run/.containerenv") || set(host.variable("container"))) {
            return Optional.of("a container (Docker or Podman)");
        }
        for (String cgroup : new String[] {"/proc/self/cgroup", "/proc/1/cgroup"}) {
            if (CONTAINER_CGROUP.matcher(host.read(cgroup)).find()) {
                return Optional.of("a container (cgroup " + cgroup + ")");
            }
        }
        return Optional.empty();
    }

    /** True when Spring's test framework is building this context (it is on the stack of the thread that does it). */
    static boolean inSpringTestContext() {
        return StackWalker.getInstance().walk((Stream<StackWalker.StackFrame> frames) -> frames
                .anyMatch(frame -> frame.getClassName().startsWith("org.springframework.test.context.")
                        || frame.getClassName().startsWith("org.springframework.boot.test.context.")));
    }

    private static boolean set(String value) {
        return value != null && !value.isBlank();
    }

    static String refusal(String signal) {
        return "the 'dev' profile is active in " + signal + ": it removes TLS, mTLS and Redis from the server and is "
                + "meant for a developer's machine; remove SPRING_PROFILES_ACTIVE=dev (and 'dev' from any .env or unit "
                + "file) from this deployment";
    }
}
