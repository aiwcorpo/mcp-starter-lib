package pl.aiwcorpo.mcp.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.platform.tls.CertificateFingerprint;
import pl.aiwcorpo.mcp.platform.tls.SelfSignedCertificate;

/** The TLS of the server port from the environment variables of the author (CC-249), without a server. */
class PlatformTlsEnvironmentTest {

    private static MockEnvironment production(Path stateDir) {
        MockEnvironment environment = new MockEnvironment().withProperty("aiwcorpo.mcp.platform.server-id", "mcp-jira");
        if (stateDir != null) {
            environment.setProperty("MCP_STATE_DIR", stateDir.toString());
        }
        environment.setActiveProfiles("prod");
        return environment;
    }

    @Test
    void withoutAnyCertificateOneIsGeneratedOnceIntoTheStateDirectoryAndReused(@TempDir Path state) throws Exception {
        MockEnvironment first = production(state);
        PlatformTlsEnvironment.apply(first);

        Path cert = state.resolve("tls/server.crt");
        Path key = state.resolve("tls/server.key");
        assertThat(cert).isRegularFile();
        assertThat(key).isRegularFile();
        assertThat(first.getProperty("server.ssl.bundle")).isEqualTo("mcp-server");
        assertThat(first.getProperty("spring.ssl.bundle.pem.mcp-server.keystore.certificate")).isEqualTo("file:" + cert);
        assertThat(first.getProperty("spring.ssl.bundle.pem.mcp-server.keystore.private-key")).isEqualTo("file:" + key);
        assertThat(first.getProperty(PlatformTlsEnvironment.SOURCE)).isEqualTo("generated");
        String fingerprint = first.getProperty(PlatformTlsEnvironment.FINGERPRINT);
        assertThat(fingerprint).matches("^sha256:[0-9a-f]{64}$")
                .isEqualTo(CertificateFingerprint.of(PlatformTlsEnvironment.readCertificate(cert)));
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(key))).isEqualTo("rw-------");

        MockEnvironment second = production(state);
        PlatformTlsEnvironment.apply(second);

        assertThat(second.getProperty(PlatformTlsEnvironment.FINGERPRINT)).as("the same certificate at the next start").isEqualTo(fingerprint);
    }

    @Test
    void anExpiredGeneratedCertificateIsReplaced(@TempDir Path state) throws Exception {
        Path directory = Files.createDirectories(state.resolve("tls"));
        SelfSignedCertificate.Generated old = SelfSignedCertificate.generate("mcp-jira", List.of("localhost"), List.of(),
                Instant.now().minus(Duration.ofDays(30)), Duration.ofDays(1));
        Files.writeString(directory.resolve("server.crt"), old.certificatePem());
        Files.writeString(directory.resolve("server.key"), old.privateKeyPem());
        MockEnvironment environment = production(state);

        PlatformTlsEnvironment.apply(environment);

        assertThat(environment.getProperty(PlatformTlsEnvironment.FINGERPRINT)).isNotEqualTo(CertificateFingerprint.of(old.certificate()));
    }

    @Test
    void plainHttpSwitchesTlsOffAndReadsNothingElse(@TempDir Path state) {
        MockEnvironment environment = production(state).withProperty("MCP_PLAIN_HTTP", "true").withProperty("MCP_TLS_KEYSTORE", "/does/not/exist");

        PlatformTlsEnvironment.apply(environment);

        assertThat(environment.getProperty("server.ssl.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("server.ssl.bundle")).isNull();
        assertThat(environment.getProperty(PlatformTlsEnvironment.SOURCE)).isEqualTo("plain-http");
        assertThat(state.resolve("tls")).doesNotExist();
    }

    @Test
    void anOwnPemCertificateIsUsedAsItIsAndItsFingerprintIsReported(@TempDir Path directory) throws Exception {
        SelfSignedCertificate.Generated own = SelfSignedCertificate.generate("own", List.of("localhost"), List.of(InetAddress.getLoopbackAddress()),
                Instant.now(), Duration.ofDays(30));
        Path cert = Files.writeString(directory.resolve("own.crt"), own.certificatePem());
        Path key = Files.writeString(directory.resolve("own.key"), own.privateKeyPem());
        MockEnvironment environment = production(directory.resolve("state")).withProperty("MCP_TLS_CERT", cert.toString())
                .withProperty("MCP_TLS_KEY", key.toString());

        PlatformTlsEnvironment.apply(environment);

        assertThat(environment.getProperty(PlatformTlsEnvironment.SOURCE)).isEqualTo("pem");
        assertThat(environment.getProperty(PlatformTlsEnvironment.FINGERPRINT)).isEqualTo(CertificateFingerprint.of(own.certificate()));
        assertThat(environment.getProperty("spring.ssl.bundle.pem.mcp-server.keystore.certificate")).isEqualTo("file:" + cert);
        assertThat(directory.resolve("state")).as("nothing is generated").doesNotExist();

        assertThatThrownBy(() -> PlatformTlsEnvironment.apply(production(null).withProperty("MCP_TLS_CERT", cert.toString())))
                .hasMessageContaining("MCP_TLS_CERT and MCP_TLS_KEY go together");
    }

    @Test
    void anOwnKeystoreNeedsItsPasswordAndItsFingerprintIsReported(@TempDir Path directory) throws Exception {
        SelfSignedCertificate.Generated own = SelfSignedCertificate.generate("own", List.of("localhost"), List.of(), Instant.now(),
                Duration.ofDays(30));
        String body = own.privateKeyPem().replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("server", key, "changeit".toCharArray(), new java.security.cert.Certificate[] {own.certificate()});
        Path keystore = directory.resolve("server.p12");
        try (var out = Files.newOutputStream(keystore)) {
            store.store(out, "changeit".toCharArray());
        }

        MockEnvironment environment = production(null).withProperty("MCP_TLS_KEYSTORE", keystore.toString())
                .withProperty("MCP_TLS_STORE_PASSWORD", "changeit");
        PlatformTlsEnvironment.apply(environment);

        assertThat(environment.getProperty(PlatformTlsEnvironment.SOURCE)).isEqualTo("keystore");
        assertThat(environment.getProperty(PlatformTlsEnvironment.FINGERPRINT)).isEqualTo(CertificateFingerprint.of(own.certificate()));
        assertThat(environment.getProperty("spring.ssl.bundle.jks.mcp-server.keystore.type")).isEqualTo("PKCS12");
        assertThat(environment.getProperty("server.ssl.bundle")).isEqualTo("mcp-server");

        assertThatThrownBy(() -> PlatformTlsEnvironment.apply(production(null).withProperty("MCP_TLS_KEYSTORE", keystore.toString())))
                .hasMessageContaining("MCP_TLS_STORE_PASSWORD");
        assertThatThrownBy(() -> PlatformTlsEnvironment.apply(production(null).withProperty("MCP_TLS_KEYSTORE", keystore.toString())
                .withProperty("MCP_TLS_STORE_PASSWORD", "wrong"))).hasMessageContaining("cannot be read");
    }

    @Test
    void anUnwritableStateDirectoryStopsTheStartWithWhatToDo(@TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("not-a-directory"), "x");

        assertThatThrownBy(() -> PlatformTlsEnvironment.apply(production(file)))
                .hasMessageContaining("cannot generate the self-signed certificate").hasMessageContaining("MCP_STATE_DIR")
                .hasMessageContaining("MCP_PLAIN_HTTP=true");
    }

    @Test
    void devAndTheServersOwnBundleAreLeftAlone(@TempDir Path state) {
        MockEnvironment dev = production(state);
        dev.setActiveProfiles("dev");
        PlatformTlsEnvironment.apply(dev);
        assertThat(dev.getProperty("server.ssl.bundle")).isNull();

        MockEnvironment own = production(state).withProperty("server.ssl.bundle", "mine");
        PlatformTlsEnvironment.apply(own);
        assertThat(own.getProperty("server.ssl.bundle")).isEqualTo("mine");
        assertThat(own.getProperty(PlatformTlsEnvironment.SOURCE)).isNull();
        assertThat(state.resolve("tls")).doesNotExist();
    }

    @Test
    void theStateDirectoryIsThePropertyThenTheVariableThenTheDefault() {
        assertThat(PlatformTlsEnvironment.stateDir(new MockEnvironment().withProperty("aiwcorpo.mcp.platform.state-dir", "/a")
                .withProperty("MCP_STATE_DIR", "/b"))).isEqualTo("/a");
        assertThat(PlatformTlsEnvironment.stateDir(new MockEnvironment().withProperty("MCP_STATE_DIR", "/b"))).isEqualTo("/b");
        assertThat(PlatformTlsEnvironment.stateDir(new MockEnvironment())).isEqualTo("/var/lib/mcp/state");
    }

    @Test
    void theVariablesOfADeploymentBecomeDefaultsOfThePlatformProperties() {
        MockEnvironment environment = new MockEnvironment().withProperty("MCP_STATE_DIR", "/data/state")
                .withProperty("MCP_GATEWAY_JWKS_URI", "https://mcp.corp.test/.well-known/mcp-jwks.json")
                .withProperty("MCP_PAGING_CURSOR_KEY", "a2V5").withProperty("MCP_REDIS_HOST", "redis.corp.test")
                .withProperty("MCP_REDIS_PORT", "6380").withProperty("MCP_REDIS_USER", "").withProperty("MCP_PLAIN_HTTP", "true");

        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("aiwcorpo.mcp.platform.state-dir")).isEqualTo("/data/state");
        assertThat(environment.getProperty("aiwcorpo.mcp.platform.internal-jwt.jwks-uri")).isEqualTo("https://mcp.corp.test/.well-known/mcp-jwks.json");
        assertThat(environment.getProperty("aiwcorpo.mcp.platform.paging.cursor-key")).isEqualTo("a2V5");
        assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("redis.corp.test");
        assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6380");
        assertThat(environment.getProperty("spring.data.redis.username")).as("a blank variable sets nothing").isNull();
    }
}
