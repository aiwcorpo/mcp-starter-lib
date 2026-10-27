package pl.aiwcorpo.mcp.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the backend credential off the request into {@link CredentialContext} and hides every credential
 * header (and {@code Authorization}) from the rest of the request, so no later component (the MCP transport,
 * a tool reading the transport context, a request logger) can see or log them.
 *
 * <ul>
 *   <li>{@code X-MCP-Credential: <scheme> <value>} with {@code X-MCP-Credential-Type} (C3), in every mode.</li>
 *   <li>{@code X-PAT-<system>} for the server's own system, only in {@code dev-pat} mode; taken as a
 *       {@code Bearer} {@code user-secret}.</li>
 * </ul>
 * A malformed credential, a type without a credential (or the reverse), or two credentials at once are
 * refused with 400 instead of guessing which one was meant.
 */
public class CredentialHeaderFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialHeaderFilter.class);

    /** After the internal JWT (15): an unauthenticated request never gets this far. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    public static final String CREDENTIAL_HEADER = "X-MCP-Credential";
    public static final String CREDENTIAL_TYPE_HEADER = "X-MCP-Credential-Type";

    /** Visible ASCII only: the value goes into a backend request header and must not split it. */
    private static final Pattern CREDENTIAL = Pattern.compile("^(\\S+) ([\\x21-\\x7e]+)$");

    private final String system;
    private final String devPatPrefix;     // null unless dev-pat mode

    /**
     * @param system       the backend system this server serves
     * @param devPatPrefix {@code X-PAT-} in {@code dev-pat} mode, {@code null} otherwise
     */
    public CredentialHeaderFilter(String system, String devPatPrefix) {
        this.system = system;
        this.devPatPrefix = devPatPrefix;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<BackendCredential> credential;
        try {
            credential = credentialOf(request);
        } catch (IllegalArgumentException malformed) {
            LOG.warn("event=credential_header_rejected reason=\"{}\"", malformed.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"invalid_argument\",\"message\":\"Malformed credential headers.\"}");
            return;
        }
        CredentialContext.bind(system, credential.orElse(null));
        try {
            chain.doFilter(new CredentialHidingRequest(request, devPatPrefix), response);
        } finally {
            CredentialContext.clear();
        }
    }

    /** @throws IllegalArgumentException with a reason that never contains a header value */
    private Optional<BackendCredential> credentialOf(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(CREDENTIAL_HEADER));
        List<String> types = Collections.list(request.getHeaders(CREDENTIAL_TYPE_HEADER));
        if (values.size() > 1 || types.size() > 1) {
            throw new IllegalArgumentException("repeated credential header");
        }
        Optional<BackendCredential> fromGateway = Optional.empty();
        if (!values.isEmpty() || !types.isEmpty()) {
            if (values.isEmpty() || types.isEmpty()) {
                throw new IllegalArgumentException(CREDENTIAL_HEADER + " and " + CREDENTIAL_TYPE_HEADER
                        + " must be sent together");
            }
            Matcher matcher = CREDENTIAL.matcher(values.getFirst());
            if (!matcher.matches()) {
                throw new IllegalArgumentException(CREDENTIAL_HEADER + " is not '<scheme> <value>'");
            }
            BackendCredential.Scheme scheme = BackendCredential.Scheme.fromWireName(matcher.group(1))
                    .orElseThrow(() -> new IllegalArgumentException("unknown credential scheme"));
            BackendCredential.Type type = BackendCredential.Type.fromWireName(types.getFirst())
                    .orElseThrow(() -> new IllegalArgumentException("unknown credential type"));
            fromGateway = Optional.of(new BackendCredential(scheme, matcher.group(2), type));
        }
        if (devPatPrefix == null) {
            return fromGateway;
        }
        List<String> pats = Collections.list(request.getHeaders(devPatPrefix + system));
        if (pats.isEmpty()) {
            return fromGateway;
        }
        if (fromGateway.isPresent() || pats.size() > 1) {
            throw new IllegalArgumentException("more than one credential on the request");
        }
        String pat = pats.getFirst().trim();
        if (!pat.matches("[\\x21-\\x7e]+")) {
            throw new IllegalArgumentException(devPatPrefix + "<system> is empty or not visible ASCII");
        }
        return Optional.of(new BackendCredential(BackendCredential.Scheme.BEARER, pat,
                BackendCredential.Type.USER_SECRET));
    }

    /** Hides {@code Authorization}, {@code X-MCP-Credential} and every {@code X-PAT-*} header. */
    static final class CredentialHidingRequest extends HttpServletRequestWrapper {

        private final String patPrefix;

        CredentialHidingRequest(HttpServletRequest request, String devPatPrefix) {
            super(request);
            this.patPrefix = (devPatPrefix == null ? "X-PAT-" : devPatPrefix).toLowerCase(Locale.ROOT);
        }

        private boolean hidden(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.equals("authorization") || lower.equals("x-mcp-credential") || lower.startsWith("x-pat-")
                    || lower.startsWith(patPrefix);
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(this::hidden);
            return Collections.enumeration(names);
        }

        @Override
        public long getDateHeader(String name) {
            return hidden(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return hidden(name) ? -1 : super.getIntHeader(name);
        }
    }
}
