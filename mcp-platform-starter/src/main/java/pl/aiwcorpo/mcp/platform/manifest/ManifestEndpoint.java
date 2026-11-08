package pl.aiwcorpo.mcp.platform.manifest;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /.well-known/mcp-manifest} (C14): the manifest (C6) of this server, the same document the build writes to
 * {@code target/mcp-manifest.json}. Behind the internal JWT filter like everything but the health probes: the gateway pulls it with
 * a service token of the purpose {@code manifest}. {@code 503} while the manifest is not built (the server is starting).
 */
@RestController
public final class ManifestEndpoint {

    private static final String NOT_READY = "{\"error\":\"upstream_unavailable\",\"message\":\"the manifest is not ready\"}";

    private final ManifestCheck check;

    ManifestEndpoint(ManifestCheck check) {
        this.check = check;
    }

    @GetMapping(path = "/.well-known/mcp-manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> manifest() {
        return check.manifest()
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
                .orElseGet(() -> ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(NOT_READY));
    }
}
