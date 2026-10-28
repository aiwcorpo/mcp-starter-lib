package pl.aiwcorpo.mcp.platform.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.BeansException;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import pl.aiwcorpo.mcp.platform.autoconfigure.ListenAddresses;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformPresenceCheck;

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
 * <p>That alone says nothing about the server: the rule is the starter's. What can go wrong in the server is that
 * its application context does not have the guard (an {@code exclude} of a platform auto-configuration, which a Spring
 * test context does not refuse because slice tests leave the platform out on purpose) or that its own configuration
 * sets an address other machines can reach. {@link #assertServerIsGuarded(ApplicationContext)} checks exactly that on
 * the context of the server's {@code @SpringBootTest} in the {@code dev} profile.
 *
 * <pre>{@code
 * class DevProfileLoopbackTest extends ServerTestSupport {
 *     @Autowired ApplicationContext context;
 *
 *     @Test
 *     void devListensOnLoopbackOnly() {
 *         DevProfileGuardAssertions.assertDevProfileLoopbackRule();
 *         DevProfileGuardAssertions.assertServerIsGuarded(context);
 *     }
 * }
 * }</pre>
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
        assertLoopbackRule(DevProfileGuardAssertions::guardMessage);
    }

    /** The rule against a guard given by {@code guard} (address to refusal message, {@code null} when accepted); for the helper's own tests. */
    static void assertLoopbackRule(Function<String, String> guard) {
        NETWORK_ADDRESSES.forEach(address -> assertRefuses(guard, address));
        assertRefuses(guard, "");
        for (String address : LOOPBACK_ADDRESSES) {
            String message = guard.apply(address);
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
        assertRefuses(DevProfileGuardAssertions::guardMessage, address);
    }

    private static void assertRefuses(Function<String, String> guard, String address) {
        String message = guard.apply(address);
        if (message == null) {
            throw new AssertionError("the dev profile accepts server.address '" + address + "'");
        }
        if (!message.contains(LOOPBACK_PROBLEM)) {
            throw new AssertionError("the dev profile refuses server.address '" + address + "' without naming the "
                    + "address as the problem: " + message);
        }
    }

    /**
     * The application context of a server started in the {@code dev} profile has every bean of
     * {@link PlatformPresenceCheck#REQUIRED_BEANS} (the configuration guard, the internal JWT verifier and filter, the
     * audit post-processor and the server customizer), its {@code server.address} is a loopback address and, when it
     * runs a web server, the server listens only on loopback addresses. The beans are fetched, not only looked up:
     * with {@code spring.main.lazy-initialization=true} a bean that nothing needs is defined but never created, and
     * the guard's checks run when it is created. The guard refuses a network address at startup, but only when it
     * exists and runs; this fails when it does not.
     *
     * @throws AssertionError when a bean is missing or cannot be created, or an address is not loopback
     */
    public static void assertServerIsGuarded(ApplicationContext context) {
        List<String> missing = new ArrayList<>();
        for (String name : PlatformPresenceCheck.REQUIRED_BEANS) {
            if (!context.containsBean(name)) {
                missing.add(name);
                continue;
            }
            try {
                context.getBean(name);
            } catch (BeansException ex) {
                throw new AssertionError("the platform bean '" + name + "' of the server's context cannot be created: "
                        + ex.getMostSpecificCause().getMessage(), ex);
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("the server's context lacks the platform beans " + missing + ": an auto-configuration "
                    + "of the platform is excluded, so nothing keeps the dev profile on a loopback address");
        }
        Environment environment = context.getEnvironment();
        if (!List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE))) {
            throw new AssertionError("this check is for a context started with the 'dev' profile only, active: "
                    + List.of(environment.getActiveProfiles()));
        }
        String address = environment.getProperty("server.address");
        if (!PlatformConfigurationGuard.loopback(address)) {
            throw new AssertionError("the server's server.address is '" + address + "', not a loopback address");
        }
        if (context instanceof WebServerApplicationContext web && web.getWebServer() != null) {
            List<String> exposed = ListenAddresses.nonLoopback(web.getWebServer());
            if (!exposed.isEmpty()) {
                throw new AssertionError("the server listens on " + exposed + ", not on loopback only, whatever "
                        + "server.address says");
            }
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
