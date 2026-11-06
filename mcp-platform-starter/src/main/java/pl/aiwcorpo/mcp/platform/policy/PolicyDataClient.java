package pl.aiwcorpo.mcp.platform.policy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;

/**
 * Reads the policy data from the gateway ({@code GET /internal/v1/policy-data}, C7) with {@code If-None-Match}. No redirect is
 * followed, and the body is read up to a limit and within one deadline, body included. The answer is only parsed here; what it
 * means for this server is decided when a {@link PolicySnapshot} is made from it.
 */
public final class PolicyDataClient {

    /** The largest answer read: a scope mapping of thousands of groups is far below it. */
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    /** The data and the {@code ETag} the gateway sent with it, to be repeated as {@code If-None-Match}. */
    public record Fetched(PolicyData data, String etag) {
    }

    private final HttpClient http;
    private final Duration readTimeout;

    /**
     * @param sslContext the context with the client certificate of the server, or {@code null} for the JVM default (the dev profile)
     */
    public PolicyDataClient(SSLContext sslContext, Duration connectTimeout, Duration readTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.http = builder.build();
        this.readTimeout = readTimeout;
    }

    /**
     * @param etag the ETag of the data the caller holds, or {@code null}
     * @return empty when the gateway answers {@code 304}: nothing changed
     * @throws IOException when the answer is not {@code 200} or {@code 304}, does not arrive in time, is too large or is not policy data
     */
    public Optional<Fetched> fetch(URI uri, String etag) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(readTimeout).header("Accept", "application/json").GET();
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        HttpResponse<byte[]> response = send(request.build());
        if (response.statusCode() == 304) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("policy-data: HTTP " + response.statusCode());
        }
        try {
            return Optional.of(new Fetched(PolicyData.parseOrRefuse(response.body()), response.headers().firstValue("ETag").orElse(null)));
        } catch (IllegalArgumentException ex) {
            throw new IOException("policy-data: " + ex.getMessage(), ex);
        }
    }

    /**
     * One request with a deadline for the whole of it ({@code HttpRequest#timeout} covers only the wait for the headers, so a
     * gateway that sends a byte a second would hold the thread), and a limit on the body that stops the read instead of buffering
     * past it.
     */
    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY_BYTES + 1L));
        try {
            HttpResponse<byte[]> response = future.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.body() != null && response.body().length > MAX_BODY_BYTES) {
                throw new IOException("policy-data is larger than " + MAX_BODY_BYTES + " bytes");
            }
            return response;
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IOException("policy-data was not received within " + readTimeout.toSeconds() + " s", ex);
        } catch (ExecutionException ex) {
            throw new IOException("policy-data: " + ex.getCause().getClass().getSimpleName(), ex.getCause());
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        }
    }
}
