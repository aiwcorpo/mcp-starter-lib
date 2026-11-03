package pl.aiwcorpo.mcp.platform.capability;

/**
 * Asks the backend system which version and which features it has, once at startup and then periodically. A server
 * that supports more than one version of its system (Jira Data Center 9.12 and newer, GitLab with fields that come and
 * go) declares one bean of this type; the platform runs it, keeps the answer in {@link BackendCapabilities} and
 * reports it in the log, the metric {@code mcp_backend_capability} and the {@code backendCapability} health detail.
 *
 * <p>The probe runs on a thread of its own, outside any request: there is no caller and no user credential. It reads
 * what the system says about itself at endpoints that need no login ({@code /rest/api/2/serverInfo}), never anything a
 * user owns. A server keeps no secrets (the credentials come from the broker through the gateway), so a probe of a system
 * whose version endpoint needs a login (GitLab {@code /api/v4/version}) cannot hold a token in the server's configuration:
 * that needs a decision on a service credential delivered by the broker (a contract change) and until then such a system is
 * probed through a public endpoint or not at all. It must be safe to repeat and should
 * finish well within {@code aiwcorpo.mcp.platform.capability.timeout}; an exception or a timeout is
 * {@link Capabilities.State#UNAVAILABLE}, not a failed start.
 */
public interface CapabilityProbe {

    /** Name of the backend system, snake case (for example {@code jira}); the label of the metric and the key in {@link BackendCapabilities}. */
    String name();

    Capabilities probe() throws Exception;
}
