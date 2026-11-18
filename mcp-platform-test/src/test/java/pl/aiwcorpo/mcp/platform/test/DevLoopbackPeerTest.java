package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/**
 * In the dev profile the token is signed with a published key, so it does not tell who sent it. A request that names another
 * host or comes from a page of another site (DNS rebinding) must not reach the server, whatever token it carries.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.mcp.server.name=platform-dev-peer-test")
@ActiveProfiles("dev")
@DisplayName("a dev server and a foreign Host or Origin")
class DevLoopbackPeerTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
    private static final Pattern REVISION = Pattern.compile("\"revision\"\\s*:\\s*\\d+");

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    private record Answer(int status, String head, String body) {
        String header(String name) {
            Matcher matcher = Pattern.compile("(?im)^" + Pattern.quote(name) + ":\\s*(\\S+)").matcher(head);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    /** A socket, because java.net.http.HttpClient does not let a caller set Host. */
    private Answer send(String path, String host, String origin, Map<String, String> headers, String body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder("POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
            if (origin != null) {
                head.append("Origin: ").append(origin).append("\r\n");
            }
            head.append("Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("Content-Length: ").append(content.length).append("\r\nConnection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(content);
            out.flush();
            InputStream in = socket.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int split = response.indexOf("\r\n\r\n");
            return new Answer(Integer.parseInt(response.substring(9, 12)), response.substring(0, split), response.substring(split + 4));
        }
    }

    private long held() throws IOException {
        return GATEWAY.policyData().pushTo(URI.create("http://127.0.0.1:" + port)).revision();
    }

    @Test
    void aForeignHostAndOriginAreRefusedWhateverTheToken() throws Exception {
        String host = "attacker.test:" + port;
        String origin = "http://attacker.test";
        long held = held();
        String document = REVISION.matcher(GATEWAY.policyData().json()).replaceFirst("\"revision\":" + (held + 10));

        Answer push = send("/.well-known/mcp-policy-data", host, origin,
                GATEWAY.service().use(TestGateway.USE_POLICY_DATA).policyRevision(held + 10).mint().headers(), document);
        Answer initialize = send(RawMcp.ENDPOINT, host, origin, GATEWAY.user().mint().headers(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"platform-test","version":"1"}}}""");
        Map<String, String> list = new LinkedHashMap<>(GATEWAY.user().mint().headers());
        String session = initialize.header("Mcp-Session-Id");
        if (session != null) {
            list.put("Mcp-Session-Id", session);
        }
        Answer tools = send(RawMcp.ENDPOINT, host, origin, list, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");

        assertThat(push.status()).isEqualTo(403);
        assertThat(initialize.status()).isEqualTo(403);
        assertThat(tools.status()).isEqualTo(403);
        assertThat(held()).as("the held policy data is unchanged").isEqualTo(held);
    }

    @Test
    void aLoopbackHostWithoutOriginIsServed() throws Exception {
        Answer initialize = send(RawMcp.ENDPOINT, "localhost:" + port, null, GATEWAY.user().mint().headers(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"platform-test","version":"1"}}}""");
        assertThat(initialize.status()).isEqualTo(200);
    }
}
