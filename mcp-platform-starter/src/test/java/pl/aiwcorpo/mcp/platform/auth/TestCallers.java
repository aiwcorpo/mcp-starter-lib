package pl.aiwcorpo.mcp.platform.auth;

import java.util.List;
import java.util.function.Supplier;

/** Test access to the package-private binding of {@link CallerContext}. */
public final class TestCallers {

    private TestCallers() {
    }

    public static CallerContext user(String tool) {
        return new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski", List.of("mcp-jira-read"),
                "claude-code", tool, "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "20261026120000-a1b2c3d", null);
    }

    public static CallerContext service() {
        return new CallerContext(CallerContext.SERVICE_SUBJECT, null, List.of(), null, null,
                "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e80", "20261026120000-a1b2c3d", null);
    }

    public static <T> T as(CallerContext caller, Supplier<T> action) {
        CallerContext.bind(caller);
        try {
            return action.get();
        } finally {
            CallerContext.clear();
        }
    }
}
