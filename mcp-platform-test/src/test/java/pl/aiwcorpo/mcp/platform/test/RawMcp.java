package pl.aiwcorpo.mcp.platform.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Plain HTTP against the MCP endpoint, for requests a well-behaved client would never send. */
final class RawMcp {

    static final String ENDPOINT = "/mcp";

    private final int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RawMcp(int port) {
        this.port = port;
    }

    HttpResponse<String> post(String body, Map<String, String> headers, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** initialize + notifications/initialized with fresh user tokens; returns the session id. */
    String openSession(TestGateway gateway) throws Exception {
        HttpResponse<String> initialize = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"platform-test","version":"1"}}}""",
                gateway.user().mint().headers(), null);
        if (initialize.statusCode() != 200) {
            throw new IllegalStateException("initialize answered " + initialize.statusCode());
        }
        String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", gateway.user().mint().headers(), session);
        return session;
    }

    static String toolCall(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}";
    }

    /**
     * Sends the request line exactly as given, without any client-side normalization of the path, and returns
     * the status code. The container decides what the path means, which is the point.
     */
    int rawStatus(String method, String rawPath, String body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String head = method + " " + rawPath + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                    + "Content-Length: " + content.length + "\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(content);
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = new String(in.readNBytes(12), StandardCharsets.ISO_8859_1);
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
