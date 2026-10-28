package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.util.Optional;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.core.env.Environment;

/**
 * Recognizes a runtime where the {@code dev} profile can only be a mistake. The profile takes TLS, mTLS and Redis
 * off the server, so a stray {@code SPRING_PROFILES_ACTIVE=dev} copied into a deployment would leave a production
 * server with the gateway's internal JWT as its only protection. A deployment is recognized by what a developer's
 * terminal does not have: a cloud platform Spring Boot detects (Kubernetes, Cloud Foundry, ...) or a service
 * manager's invocation id (the systemd unit of a real installation).
 */
final class DevProfileEnvironment {

    private DevProfileEnvironment() {
    }

    /** What marks the runtime as a deployment, or empty on a developer's machine. */
    static Optional<String> deploymentSignal(Environment environment) {
        CloudPlatform platform = CloudPlatform.getActive(environment);
        if (platform != null) {
            return Optional.of("cloud platform " + platform);
        }
        String invocation = environment.getProperty("INVOCATION_ID");
        if (invocation != null && !invocation.isBlank()) {
            return Optional.of("a systemd service (INVOCATION_ID is set)");
        }
        return Optional.empty();
    }

    static String refusal(String signal) {
        return "the 'dev' profile is active in " + signal + ": it removes TLS, mTLS and Redis from the server and is "
                + "meant for a developer's machine; remove SPRING_PROFILES_ACTIVE=dev (and 'dev' from any .env or unit "
                + "file) from this deployment";
    }
}
