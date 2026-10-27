package pl.aiwcorpo.mcp.platform.security;

import java.util.function.Supplier;

/** Test access to the package-private binding of {@link CredentialContext}. */
public final class TestCredentials {

    private TestCredentials() {
    }

    public static <T> T with(String system, BackendCredential credential, Supplier<T> action) {
        CredentialContext.bind(system, credential);
        try {
            return action.get();
        } finally {
            CredentialContext.clear();
        }
    }
}
