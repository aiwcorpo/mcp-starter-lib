package pl.aiwcorpo.mcp.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The name the gateway's certificate must carry, checked against the subject alternative names and not the common name. */
class GatewayTlsTest {

    private static X509Certificate certificate(Path dir, String san) throws Exception {
        Path store = dir.resolve("gateway.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair",
                "-alias", "gw", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=mcp-gateway", "-ext", "san=" + san,
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-validity", "2").redirectErrorStream(true)
                .start();
        keytool.getInputStream().readAllBytes();
        assertThat(keytool.waitFor()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        return (X509Certificate) keys.getCertificate("gw");
    }

    @Test
    void aDnsNameMatchesExactlyAndAWildcardStandsForOneLabel(@TempDir Path dir) throws Exception {
        X509Certificate exact = certificate(dir, "dns:mcp-gateway.internal");
        assertThat(GatewayTls.names(exact, "mcp-gateway.internal")).isTrue();
        assertThat(GatewayTls.names(exact, "MCP-GATEWAY.INTERNAL")).isTrue();
        assertThat(GatewayTls.names(exact, "other.internal")).isFalse();
        assertThat(GatewayTls.names(exact, "mcp-gateway")).as("the common name is CN=mcp-gateway and does not count").isFalse();

        Path other = Files.createDirectory(dir.resolve("wild"));
        X509Certificate wild = certificate(other, "dns:*.internal");
        assertThat(GatewayTls.names(wild, "mcp-gateway.internal")).isTrue();
        assertThat(GatewayTls.names(wild, "a.b.internal")).isFalse();
        assertThat(GatewayTls.names(wild, "internal")).isFalse();
    }

    @Test
    void anAddressNeedsAnIpSanNotADnsOne(@TempDir Path dir) throws Exception {
        X509Certificate byAddress = certificate(dir, "ip:10.1.2.3");
        assertThat(GatewayTls.names(byAddress, "10.1.2.3")).isTrue();
        assertThat(GatewayTls.names(byAddress, "10.1.2.4")).isFalse();

        Path other = Files.createDirectory(dir.resolve("dns"));
        assertThat(GatewayTls.names(certificate(other, "dns:10.1.2.3"), "10.1.2.3")).isFalse();
    }
}
