package pl.aiwcorpo.mcp.platform.policy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
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
     * @throws IOException when the repository does not answer 200 or 304, or the body is over its limit
     * @throws BundleRejectedException when {@code latest.json} is malformed
     */
    public Optional<Pointer> pointer(URI latest, String etag) throws IOException, InterruptedException, BundleRejectedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(latest).timeout(readTimeout).GET();
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] pointerBytes;
        try (InputStream body = response.body()) {
            if (response.statusCode() == 304) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IOException("latest.json: HTTP " + response.statusCode());
            }
            pointerBytes = read(body, MAX_POINTER_BYTES, "latest.json");
        }
        return Optional.of(new Pointer(BundlePointer.parse(pointerBytes), response.headers().firstValue("ETag").orElse(null)));
    }

    /** Downloads the archive and the signature the pointer names, from the directory of {@code latest}. */
    public Bundle download(URI latest, BundlePointer pointer) throws IOException, InterruptedException {
        return new Bundle(get(latest.resolve(pointer.url()), MAX_ARCHIVE_BYTES),
                get(latest.resolve(pointer.sigUrl()), MAX_SIGNATURE_BYTES));
    }

    private byte[] get(URI uri, int limit) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri).timeout(readTimeout).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(uri.getPath() + ": HTTP " + response.statusCode());
            }
            return read(body, limit, uri.getPath());
        }
    }

    private static byte[] read(InputStream in, int limit, String what) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            if (out.size() > limit) {
                throw new IOException(what + " is larger than " + limit + " bytes");
            }
        }
        return out.toByteArray();
    }
}
