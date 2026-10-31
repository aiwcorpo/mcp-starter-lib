package pl.aiwcorpo.mcp.platform.autoconfigure;

import org.junit.jupiter.api.Assumptions;
import org.springframework.mock.env.MockEnvironment;

/**
 * Skips a test that expects a developer's machine when the host carries a deployment signal (container, pod, systemd), judged by the
 * same detector the starter uses ({@link DevProfileEnvironment}; this copy sits in the same package to reach it). The skip is reported by the test runner, and {@code ci/run.sh release}
 * refuses to release with skipped tests.
 */
public final class DeploymentSignalAssumption {

    private DeploymentSignalAssumption() {
    }

    public static void assumeDeveloperMachine() {
        var signal = DevProfileEnvironment.deploymentSignal(new MockEnvironment(), DevProfileEnvironment.LOCAL_HOST, () -> false);
        Assumptions.assumeTrue(signal.isEmpty(), () -> "host is " + signal.orElse(""));
    }
}
