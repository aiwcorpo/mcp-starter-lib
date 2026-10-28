package pl.aiwcorpo.mcp.platform.test;

import java.util.List;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;

/**
 * Checks the starter's rule that the {@code dev} profile only listens on a loopback address, in a test that passes
 * on every host: a developer's machine, a CI runner in a container, a Kubernetes pod or a systemd service.
 *
 * <p>A server's test must not prove the rule by starting the application with {@code --server.address=0.0.0.0}
 * and reading the refusal: on a host the starter recognizes as a deployment (container, pod, systemd) the start is
 * refused earlier, for another reason, and the message about the address never appears. These helpers call
 * {@link PlatformConfigurationGuard} directly with an environment of their own: only the properties given here, no
 * process variables or system properties of the host, and the deployment signals of a Kubernetes pod and a systemd
 * unit set on purpose, so the rule is shown to hold even where the {@code dev} profile is refused for that reason too.
 *
 * <pre>{@code
 * class DevProfileLoopbackTest {
 *     @Test
 *     void devListensOnLoopbackOnly() {
 *         DevProfileGuardAssertions.assertDevProfileLoopbackRule();
 *     }
 * }
 * }</pre>
 * The rule is the starter's; the test only keeps it from disappearing unnoticed from the starter version the server
 * builds against.
 */
public final class DevProfileGuardAssertions {

    private static final String LOOPBACK_PROBLEM = "server.address must be a loopback address";
    private static final List<String> NETWORK_ADDRESSES = List.of("0.0.0.0", "::", "10.0.0.5", "192.168.1.20", "my-host");
    private static final List<String> LOOPBACK_ADDRESSES = List.of("127.0.0.1", "127.0.0.2", "::1", "localhost");

    private DevProfileGuardAssertions() {
    }

    /**
     * The {@code dev} profile refuses wildcard, private-network and host-name addresses and an empty one, and does not
     * complain about the address when it is {@code 127.0.0.1}, {@code ::1} or {@code localhost}.
     *
     * @throws AssertionError when the rule does not hold
     */
    public static void assertDevProfileLoopbackRule() {
        NETWORK_ADDRESSES.forEach(DevProfileGuardAssertions::assertDevProfileRefusesAddress);
        assertDevProfileRefusesAddress("");
        for (String address : LOOPBACK_ADDRESSES) {
            String message = guardMessage(address);
            if (message != null && message.contains(LOOPBACK_PROBLEM)) {
                throw new AssertionError("the dev profile refuses the loopback address '" + address + "': " + message);
            }
        }
    }

    /**
     * The {@code dev} profile refuses {@code address} as {@code server.address} with the loopback message (other
     * problems the configuration has, for instance a deployment signal, may be reported next to it).
     *
     * @throws AssertionError when the guard does not name the address as a problem
     */
    public static void assertDevProfileRefusesAddress(String address) {
        String message = guardMessage(address);
        if (message == null) {
            throw new AssertionError("the dev profile accepts server.address '" + address + "'");
        }
        if (!message.contains(LOOPBACK_PROBLEM)) {
            throw new AssertionError("the dev profile refuses server.address '" + address + "' without naming the "
                    + "address as the problem: " + message);
        }
    }

    private static String guardMessage(String address) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("devProfileGuardAssertions", Map.of(
                "server.address", address,
                "INVOCATION_ID", "7f3a9c1e5b2d4e8f",
                "KUBERNETES_SERVICE_HOST", "10.96.0.1",
                "KUBERNETES_SERVICE_PORT", "443")));
        environment.setActiveProfiles(PlatformConfigurationGuard.DEV_PROFILE);
        try {
            new PlatformConfigurationGuard(new McpPlatformProperties(), environment).afterPropertiesSet();
            return null;
        } catch (IllegalStateException ex) {
            return ex.getMessage();
        }
    }
}
