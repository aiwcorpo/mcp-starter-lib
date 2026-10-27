package pl.aiwcorpo.mcp.platform.security;

/**
 * The tool needs the user's credential for {@code system} and the request carries none. Reaches the client
 * as {@code credential_required} (C10) with the portal link where the user connects the account.
 */
public class CredentialRequiredException extends RuntimeException {

    private final String system;

    public CredentialRequiredException(String system) {
        super("No credential for " + system + " on this request.", null, false, false);
        this.system = system;
    }

    public String getSystem() {
        return system;
    }
}
