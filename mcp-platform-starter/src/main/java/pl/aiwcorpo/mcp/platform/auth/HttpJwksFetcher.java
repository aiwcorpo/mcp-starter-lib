package pl.aiwcorpo.mcp.platform.auth;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/**
 * Fetches the gateway's JWKS over HTTPS (JDK client). Trust comes from the given {@link SSLContext}, normally
 * built from a Spring SSL bundle with the internal CA; the JVM default otherwise. Redirects are not followed
 * and the document is limited in size, since a JWKS of two keys is a few hundred bytes.
 */
public final class HttpJwksFetcher implements JwksGatewayKeys.JwksFetcher {

    private static final int MAX_BYTES = 64 * 1024;

    private final URI uri;
    private final HttpClient client;
    private final Duration readTimeout;

    public HttpJwksFetcher(URI uri, SSLContext sslContext, Duration connectTimeout, Duration readTimeout) {
        this.uri = uri;
        this.readTimeout = readTimeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.client = builder.build();
    }

    @Override
    public String fetch() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(readTimeout)
                .header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("JWKS endpoint answered HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("JWKS document larger than " + MAX_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
