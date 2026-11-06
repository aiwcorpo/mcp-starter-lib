package pl.aiwcorpo.mcp.platform.policy;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.boot.ssl.SslBundle;

/**
 * The TLS context of the connection to the gateway's internal port: the client certificate is the server's own (the gateway reads
 * the {@code serverId} from its CN), the trust is the internal CA of the bundle. The address of the gateway is often an IP or a
 * service name that is not in its certificate, so the name the certificate must carry can be set apart from the URL
 * ({@code policy-data.tls-name}).
 */
final class GatewayTls {

    private static final Pattern IP_LITERAL = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$|^[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*$");

    private GatewayTls() {
    }

    /**
     * @param tlsName the name the gateway's certificate must carry (a {@code dNSName} or, for an address, an {@code iPAddress}
     *                subject alternative name), or {@code null} to verify the host of the URL as usual
     */
    static SSLContext sslContext(SslBundle bundle, String tlsName) throws GeneralSecurityException {
        if (tlsName == null || tlsName.isBlank()) {
            return bundle.createSslContext();
        }
        TrustManager[] trust = bundle.getManagers().getTrustManagers();
        TrustManager[] named = new TrustManager[trust.length];
        for (int i = 0; i < trust.length; i++) {
            if (!(trust[i] instanceof X509TrustManager chainCheck)) {
                throw new GeneralSecurityException("the SSL bundle has a trust manager that is not an X509TrustManager");
            }
            named[i] = new NamedHostTrustManager(chainCheck, tlsName);
        }
        KeyManager[] keys = bundle.getManagers().getKeyManagers();
        SSLContext context = SSLContext.getInstance(bundle.getProtocol());
        context.init(keys, named, null);
        return context;
    }

    /**
     * Checks the chain with the CA of the bundle and then that the leaf certificate names {@code expectedName}. The chain check is
     * the two-argument one on purpose: the three-argument form of the JDK's own manager would also compare the certificate with the
     * host of the URL, which is the name this class replaces.
     */
    static final class NamedHostTrustManager extends X509ExtendedTrustManager {

        private final X509TrustManager chainCheck;
        private final String expectedName;

        NamedHostTrustManager(X509TrustManager chainCheck, String expectedName) {
            this.chainCheck = chainCheck;
            this.expectedName = expectedName;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            chainCheck.checkServerTrusted(chain, authType);
            if (chain == null || chain.length == 0 || !names(chain[0], expectedName)) {
                throw new CertificateException("the certificate of the gateway does not carry the name " + expectedName);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            chainCheck.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            chainCheck.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            chainCheck.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return chainCheck.getAcceptedIssuers();
        }
    }

    /** Whether the certificate carries {@code name} as a subject alternative name; the common name is not consulted (RFC 6125). */
    static boolean names(X509Certificate certificate, String name) throws CertificateParsingException {
        Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
        if (alternatives == null) {
            return false;
        }
        boolean address = IP_LITERAL.matcher(name).matches();
        String wanted = name.toLowerCase(Locale.ROOT);
        for (List<?> alternative : alternatives) {
            int type = (Integer) alternative.get(0);
            String value = String.valueOf(alternative.get(1)).toLowerCase(Locale.ROOT);
            if (address ? type == 7 && sameAddress(value, wanted) : type == 2 && dnsMatches(value, wanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameAddress(String certificateAddress, String wanted) {
        try {
            return java.net.InetAddress.getByName(certificateAddress).equals(java.net.InetAddress.getByName(wanted));
        } catch (java.net.UnknownHostException ex) {
            return false;
        }
    }

    /** A wildcard stands for exactly one whole left-most label, as in RFC 6125, so {@code *.internal} does not name {@code a.b.internal}. */
    private static boolean dnsMatches(String pattern, String name) {
        if (!pattern.startsWith("*.")) {
            return pattern.equals(name);
        }
        int dot = name.indexOf('.');
        return dot > 0 && name.substring(dot).equals(pattern.substring(1));
    }
}
