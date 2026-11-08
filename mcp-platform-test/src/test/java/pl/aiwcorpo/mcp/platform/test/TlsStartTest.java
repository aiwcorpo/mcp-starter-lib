package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import pl.aiwcorpo.mcp.platform.test.tls.TlsServer;
import pl.aiwcorpo.mcp.platform.tls.CertificateFingerprint;

/**
 * The server port as a deployment has it (not the {@code dev} profile, not a Spring test context, so the starter does what it does in
 * production): a self-signed certificate generated once into the state directory, served over TLS without asking the gateway for a client
 * certificate, with its fingerprint in the log at start in the format of the contracts, and the same certificate after a restart.
 */
@ExtendWith(OutputCaptureExtension.class)
class TlsStartTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    private static Map<String, Object> properties(Path state) {
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.remove("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys");
        properties.remove("aiwcorpo.mcp.platform.test.gateway");
        properties.put("aiwcorpo.mcp.platform.internal-jwt.jwks-uri", "https://mcp.corp.test/.well-known/mcp-jwks.json");
        properties.put("spring.ai.mcp.server.name", "tls-start-test");
        properties.put("management.endpoints.web.exposure.include", "health");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("MCP_STATE_DIR", state.toString());
        return properties;
    }

    private static SSLContext trustAll() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        return context;
    }

    /** The certificate the server presents, as the gateway would read it when it connects (no client certificate is offered). */
    private static X509Certificate presented(int port) throws Exception {
        try (SSLSocket socket = (SSLSocket) trustAll().getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    @Test
    void aSelfSignedCertificateIsGeneratedOnceServedWithoutAClientCertificateAndItsFingerprintIsLogged(@TempDir Path state, CapturedOutput output)
            throws Exception {
        String firstFingerprint;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TlsServer.class).web(WebApplicationType.SERVLET)
                .properties(properties(state)).run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            firstFingerprint = CertificateFingerprint.of(presented(port));

            assertThat(output.getOut()).contains("event=tls_certificate source=generated fingerprint=" + firstFingerprint);
            assertThat(firstFingerprint).matches("^sha256:[0-9a-f]{64}$");
            assertThat(CertificateFingerprint.of((X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(Files.newInputStream(state.resolve("tls/server.crt"))))).isEqualTo(firstFingerprint);

            HttpClient https = HttpClient.newBuilder().sslContext(trustAll()).connectTimeout(Duration.ofSeconds(5)).build();
            assertThat(https.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).as("the health endpoint answers without a token").isEqualTo(200);
            HttpResponse<String> mcp = https.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/mcp"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(mcp.statusCode()).as("no client certificate is needed to reach the JWT check").isEqualTo(401);
            assertThat(mcp.body()).contains("\"reason\":\"missing_token\"");
            HttpResponse<String> manifest = https.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/.well-known/mcp-manifest")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(manifest.statusCode()).as("every path but the health check needs a gateway JWT").isEqualTo(401);
            assertThat(https.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/actuator/prometheus")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TlsServer.class).web(WebApplicationType.SERVLET)
                .properties(properties(state)).run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            assertThat(CertificateFingerprint.of(presented(port))).as("the same certificate after a restart").isEqualTo(firstFingerprint);
        }
    }

    @Test
    void plainHttpIsServedOnlyWhenAskedFor(@TempDir Path state) throws Exception {
        Map<String, Object> properties = properties(state);
        properties.put("MCP_PLAIN_HTTP", "true");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TlsServer.class).web(WebApplicationType.SERVLET)
                .properties(properties).run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpResponse<String> health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(state.resolve("tls")).as("no certificate is generated for plain http").doesNotExist();
        }
    }
}
