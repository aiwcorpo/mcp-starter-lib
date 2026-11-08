package pl.aiwcorpo.mcp.platform.policy;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /.well-known/mcp-policy-data} of the sidecar of an adapter. The gateway pushes to every server alike, but an adapter has
 * no policies of the platform to evaluate (the gateway decides for it and sends the classification in the token), so the data is checked for
 * its shape and only the highest revision is remembered. The answer is the one of C14, with {@code gate: ok} because there is no gate here to refuse.
 */
@RestController
public final class AdapterPolicyDataEndpoint {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicLong revision = new AtomicLong();

    AdapterPolicyDataEndpoint() {
    }

    @PostMapping(path = "/.well-known/mcp-policy-data", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> push(@RequestBody byte[] body) {
        long offered;
        try {
            offered = PolicyData.parse(body).revision();
        } catch (IllegalArgumentException ex) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "invalid_request");
            error.put("message", ex.getMessage());
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(error.toString());
        }
        long before = revision.getAndAccumulate(offered, Math::max);
        ObjectNode answer = JSON.createObjectNode();
        answer.put("revision", Math.max(before, offered));
        answer.put("accepted", offered > before);
        answer.put("gate", "ok");
        return ResponseEntity.ok(answer.toString());
    }
}
