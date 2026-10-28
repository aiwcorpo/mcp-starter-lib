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
import pl.aiwcorpo.mcp.policy.bundle.BundlePointer;
import pl.aiwcorpo.mcp.policy.bundle.BundleRejectedException;

/**
 * Pulls a policy bundle (C7): {@code latest.json} with {@code ETag}, then the archive and its signature that the
 * pointer names. Verification is not done here ({@code PolicyStore} does it). No redirects are followed, every body is
 * read up to a limit, and {@code url}/{@code sigUrl} are resolved only as file names next to {@code latest.json}
 * ({@link BundlePointer#check()} refuses anything else).
 */
public final class PolicyBundleClient {

    /** Largest archive (and so the largest download), the same as the limit after decompression in mcp-policy-bundle. */
    public static final int MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;

    private static final int MAX_POINTER_BYTES = 16 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 4 * 1024;

    /** The current {@code latest.json} and its ETag. */
    public record Pointer(BundlePointer pointer, String etag) {
    }

    /** The archive and the signature the pointer names. */
    public record Bundle(byte[] archive, byte[] signature) {
    }

    private final HttpClient http;
    /** The time one request may take altogether, body included. */
    private final Duration readTimeout;

    public PolicyBundleClient(SSLContext sslContext, Duration connectTimeout, Duration readTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.http = builder.build();
        this.readTimeout = readTimeout;
    }

    /**
     * @param etag the ETag of the pointer the caller has already processed, or {@code null}
     * @return empty when the pointer has not changed (304)
     * @throws IOException when the repository does not answer 200 or 304, the body is over its limit or the answer does
     *         not arrive, body included, within the time limit
     * @throws BundleRejectedException when {@code latest.json} is malformed
     */
    public Optional<Pointer> pointer(URI latest, String etag) throws IOException, InterruptedException, BundleRejectedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(latest).timeout(readTimeout).GET();
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        HttpResponse<byte[]> response = send(request.build(), MAX_POINTER_BYTES, "latest.json");
        if (response.statusCode() == 304) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("latest.json: HTTP " + response.statusCode());
        }
        return Optional.of(new Pointer(BundlePointer.parse(response.body()), response.headers().firstValue("ETag").orElse(null)));
    }

    /** Downloads the archive and the signature the pointer names, from the directory of {@code latest}. */
    public Bundle download(URI latest, BundlePointer pointer) throws IOException, InterruptedException {
        return new Bundle(get(latest.resolve(pointer.url()), MAX_ARCHIVE_BYTES),
                get(latest.resolve(pointer.sigUrl()), MAX_SIGNATURE_BYTES));
    }

    private byte[] get(URI uri, int limit) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri).timeout(readTimeout).GET().build(), limit, uri.getPath());
        if (response.statusCode() != 200) {
            throw new IOException(uri.getPath() + ": HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * One request with a deadline for the whole of it, the body included ({@code HttpRequest#timeout} covers only the
     * wait for the headers, so a repository that sends a byte a second would hold the thread for as long as it likes), and
     * a limit on the body that stops the read instead of buffering past it.
     */
    private HttpResponse<byte[]> send(HttpRequest request, int limit, String what) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), limit + 1L));
        try {
            HttpResponse<byte[]> response = future.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.body() != null && response.body().length > limit) {
                throw new IOException(what + " is larger than " + limit + " bytes");
            }
            return response;
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IOException(what + " was not received within " + readTimeout.toSeconds() + " s", ex);
        } catch (ExecutionException ex) {
            throw new IOException(what + ": " + ex.getCause().getClass().getSimpleName(), ex.getCause());
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        }
    }
}
