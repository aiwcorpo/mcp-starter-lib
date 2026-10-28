package pl.aiwcorpo.mcp.platform.manifest;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /mcp-manifest} (C6): the manifest of this server. Behind the internal JWT filter like everything but the
 * health probes: the gateway reads it with the user or the service variant of its token, no tool claim.
 */
@RestController
public final class ManifestEndpoint {

    private final ManifestCheck check;

    ManifestEndpoint(ManifestCheck check) {
        this.check = check;
    }

    @GetMapping(path = "/mcp-manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> manifest() {
        return check.manifest()
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
                .orElseGet(() -> ResponseEntity.status(503).build());
    }
}
