package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import pl.aiwcorpo.mcp.platform.tls.CertificateFingerprint;
import pl.aiwcorpo.mcp.platform.tls.SelfSignedCertificate;

/**
 * TLS of the server port (CC-249), from the environment variables the author of a server sets, translated into the properties of Spring
 * Boot's SSL bundle. There is no client certificate on this port: the gateway proves itself with the internal JWT, not with TLS.
 *
 * <ol>
 *   <li>{@code MCP_PLAIN_HTTP=true}: no TLS, for a server that runs inside the Docker network of the platform, where the administrator allows
 *       {@code http} for it. Nothing else is read.</li>
 *   <li>{@code MCP_TLS_KEYSTORE} (PKCS12, or JKS when the name ends in {@code .jks}) with {@code MCP_TLS_STORE_PASSWORD}: the author's own certificate.</li>
 *   <li>{@code MCP_TLS_CERT} and {@code MCP_TLS_KEY}: the author's own certificate and its private key as PEM files (PKCS#8, not encrypted).</li>
 *   <li>Otherwise a self-signed certificate, generated once into {@code <state dir>/tls/} and reused at every start, so its fingerprint stays
 *       what the operator approved. The state directory is {@code MCP_STATE_DIR} ({@value McpPlatformProperties#DEFAULT_STATE_DIR}).</li>
 * </ol>
 * The fingerprint of the certificate that is served is left in the property {@link #FINGERPRINT} for {@code PlatformConfigurationGuard} to log at start
 * (logging is not set up yet at this point).
 *
 * <p>Not applied with {@code dev} as the only profile (it has no TLS), in a Spring test context, or when the server brings its own
 * {@code server.ssl.bundle} or {@code server.ssl.key-store}.
 */
final class PlatformTlsEnvironment {

    static final String SOURCE_NAME = "mcpPlatformTls";
    static final String BUNDLE = "mcp-server";

    /** Where the certificate that is served came from: {@code keystore}, {@code pem}, {@code generated} or {@code plain-http}. */
    static final String SOURCE = "mcp.platform.internal.tls-source";
    static final String FINGERPRINT = "mcp.platform.internal.tls-fingerprint";
    static final String DETAIL = "mcp.platform.internal.tls-detail";

    /** Ten years: a renewal of a self-signed certificate is a new fingerprint to approve, so it should not come by surprise. */
    private static final Duration GENERATED_VALIDITY = Duration.ofDays(3650);

    private PlatformTlsEnvironment() {
    }

    static boolean plainHttp(org.springframework.core.env.Environment environment) {
        return "true".equalsIgnoreCase(environment.getProperty("MCP_PLAIN_HTTP", "").trim());
    }

    static void apply(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(SOURCE_NAME) || DevProfileEnvironment.inSpringTestContext()) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE))) {
            return;
        }
        if (set(environment.getProperty("server.ssl.bundle")) || set(environment.getProperty("server.ssl.key-store"))) {
            return;
        }
        Map<String, Object> properties = new HashMap<>();
        if (plainHttp(environment)) {
            properties.put("server.ssl.enabled", "false");
            properties.put(SOURCE, "plain-http");
        } else {
            String keystore = environment.getProperty("MCP_TLS_KEYSTORE");
            String cert = environment.getProperty("MCP_TLS_CERT");
            String key = environment.getProperty("MCP_TLS_KEY");
            if (set(keystore)) {
                useKeystore(environment, keystore.trim(), properties);
            } else if (set(cert) || set(key)) {
                if (!set(cert) || !set(key)) {
                    throw new IllegalStateException("MCP_TLS_CERT and MCP_TLS_KEY go together: set both (PEM files) or neither");
                }
                usePem(cert.trim(), key.trim(), "pem", "own certificate and key", properties);
            } else {
                useGenerated(environment, properties);
            }
            properties.put("server.ssl.bundle", BUNDLE);
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, properties));
    }

    private static void useKeystore(ConfigurableEnvironment environment, String keystore, Map<String, Object> properties) {
        String password = environment.getProperty("MCP_TLS_STORE_PASSWORD");
        if (!set(password)) {
            throw new IllegalStateException("MCP_TLS_KEYSTORE is set without MCP_TLS_STORE_PASSWORD, the password of the keystore");
        }
        String type = keystore.toLowerCase(java.util.Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
        properties.put("spring.ssl.bundle.jks." + BUNDLE + ".keystore.location", "file:" + keystore);
        properties.put("spring.ssl.bundle.jks." + BUNDLE + ".keystore.password", password);
        properties.put("spring.ssl.bundle.jks." + BUNDLE + ".keystore.type", type);
        properties.put(SOURCE, "keystore");
        properties.put(DETAIL, keystore);
        try (InputStream in = Files.newInputStream(Path.of(keystore))) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password.toCharArray());
            for (String alias : java.util.Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias) && store.getCertificate(alias) instanceof X509Certificate certificate) {
                    properties.put(FINGERPRINT, CertificateFingerprint.of(certificate));
                    return;
                }
            }
            throw new IllegalStateException("MCP_TLS_KEYSTORE " + keystore + " holds no private key with a certificate");
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("MCP_TLS_KEYSTORE " + keystore + " cannot be read (is MCP_TLS_STORE_PASSWORD right?): "
                    + ex.getClass().getSimpleName(), ex);
        }
    }

    private static void usePem(String cert, String key, String source, String detail, Map<String, Object> properties) {
        properties.put("spring.ssl.bundle.pem." + BUNDLE + ".keystore.certificate", "file:" + cert);
        properties.put("spring.ssl.bundle.pem." + BUNDLE + ".keystore.private-key", "file:" + key);
        properties.put(SOURCE, source);
        properties.put(DETAIL, detail + " " + cert);
        properties.put(FINGERPRINT, CertificateFingerprint.of(readCertificate(Path.of(cert))));
        if (!Files.isReadable(Path.of(key))) {
            throw new IllegalStateException("the private key " + key + " cannot be read");
        }
    }

    private static void useGenerated(ConfigurableEnvironment environment, Map<String, Object> properties) {
        Path directory = Path.of(stateDir(environment)).resolve("tls");
        Path cert = directory.resolve("server.crt");
        Path key = directory.resolve("server.key");
        X509Certificate existing = null;
        if (Files.isReadable(cert) && Files.isReadable(key)) {
            try {
                existing = readCertificate(cert);
                existing.checkValidity();
            } catch (IllegalStateException | java.security.cert.CertificateException ex) {
                existing = null;
            }
        }
        if (existing == null) {
            generate(environment, directory, cert, key);
        }
        usePem(cert.toString(), key.toString(), "generated", "self-signed certificate in the state directory", properties);
    }

    private static void generate(ConfigurableEnvironment environment, Path directory, Path cert, Path key) {
        String serverId = environment.getProperty("aiwcorpo.mcp.platform.server-id");
        List<String> names = new ArrayList<>(List.of("localhost"));
        try {
            names.add(InetAddress.getLocalHost().getHostName());
        } catch (IOException ex) {
            // A host that cannot name itself still gets the certificate: the gateway pins it by fingerprint, not by name.
        }
        Set<String> dns = new LinkedHashSet<>(names.stream().filter(PlatformTlsEnvironment::dnsName).toList());
        try {
            SelfSignedCertificate.Generated generated = SelfSignedCertificate.generate(set(serverId) ? serverId : "mcp-server",
                    List.copyOf(dns), List.of(InetAddress.getLoopbackAddress()), Instant.now().minus(Duration.ofMinutes(5)), GENERATED_VALIDITY);
            Files.createDirectories(directory);
            restrict(directory, "rwx------");
            write(key, generated.privateKeyPem(), "rw-------");
            write(cert, generated.certificatePem(), "rw-r--r--");
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("cannot generate the self-signed certificate into " + directory + ": the state directory ("
                    + "MCP_STATE_DIR, " + McpPlatformProperties.DEFAULT_STATE_DIR + " by default) must be writable and kept between starts "
                    + "(a volume); or give the server a certificate (MCP_TLS_KEYSTORE, or MCP_TLS_CERT and MCP_TLS_KEY), or set MCP_PLAIN_HTTP=true "
                    + "inside the platform network", ex);
        }
    }

    private static boolean dnsName(String name) {
        return name != null && name.matches("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    }

    /** Written next to the target and moved into place, so a start that is stopped halfway never leaves a key without its certificate. */
    private static void write(Path target, String content, String permissions) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            restrict(temporary, permissions);
            Files.writeString(temporary, content, StandardCharsets.US_ASCII);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException ex) {
            // A file system without POSIX permissions (Windows): the directory's own access rules apply.
        }
    }

    static X509Certificate readCertificate(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("the certificate " + file + " cannot be read as a PEM file", ex);
        }
    }

    static String stateDir(org.springframework.core.env.Environment environment) {
        String configured = environment.getProperty("aiwcorpo.mcp.platform.state-dir");
        if (set(configured)) {
            return configured.trim();
        }
        String variable = environment.getProperty("MCP_STATE_DIR");
        return set(variable) ? variable.trim() : McpPlatformProperties.DEFAULT_STATE_DIR;
    }

    private static boolean set(String value) {
        return value != null && !value.isBlank();
    }
}
