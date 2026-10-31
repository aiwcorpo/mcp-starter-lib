package pl.aiwcorpo.mcp.platform.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.web.WebMergedContextConfiguration;

/**
 * Starts a server through Spring Boot's test context loader, with a mock web environment (no port). The starter takes a context built
 * by the test framework for a test (it is no deployment, whatever the host is: a CI container, a pod), so a test that only needs to
 * see a server start, or refuse to start, does not depend on where it runs. A test that needs a port is a {@code @SpringBootTest}
 * class. A failed start throws as {@code SpringApplication.run} does.
 */
public final class SpringTestContextStart {

    private SpringTestContextStart() {
    }

    public static ConfigurableApplicationContext start(Class<?> source, Map<String, ?> properties, String... profiles) {
        return start(new Class<?>[] {source}, properties, profiles);
    }

    public static ConfigurableApplicationContext start(Class<?>[] sources, Map<String, ?> properties, String... profiles) {
        List<String> inlined = new ArrayList<>();
        properties.forEach((key, value) -> inlined.add(key + "=" + escape(String.valueOf(value))));
        MergedContextConfiguration base = new MergedContextConfiguration(SpringTestContextStart.class, new String[0],
                sources, Set.of(), profiles, new String[0], inlined.toArray(String[]::new), new SpringBootContextLoader(), null, null);
        MergedContextConfiguration configuration = new WebMergedContextConfiguration(base, "");
        try {
            return (ConfigurableApplicationContext) new SpringBootContextLoader().loadContext(configuration);
        } catch (Exception ex) {
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
        }
    }

    /** Inlined properties are read as Properties text: a backslash and a line break in a value (a PEM key) must be escaped. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
