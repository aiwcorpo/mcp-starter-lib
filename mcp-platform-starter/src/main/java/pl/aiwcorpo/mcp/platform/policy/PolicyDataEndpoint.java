package pl.aiwcorpo.mcp.platform.policy;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /.well-known/mcp-policy-data} (C14): the gateway replaces the policy data of this server. Behind the internal JWT filter
 * like everything but the health probes, with a token of the purpose {@code policy-data} only.
 *
 * <p>The data replaces what is held only with a strictly higher revision; an equal or lower one is answered {@code 200} with
 * {@code accepted: false} and the revision kept. Data that makes the gate refuse (an override that does not validate, policies that
 * differ from the approved ones) is still taken and answered {@code 200}: the {@code gate} of the answer says what the server now does, and
 * the administrator sees why it refuses. A body that is not the document of the contract is {@code 400} and changes nothing.
 */
@RestController
public final class PolicyDataEndpoint {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PolicyEngine engine;
    PolicyDataEndpoint(PolicyEngine engine) {
        this.engine = engine;
    }

    @PostMapping(path = "/.well-known/mcp-policy-data", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> push(@RequestBody byte[] body) {
        PolicyData data;
        try {
            data = PolicyData.parse(body);
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
        PolicyEngine.Pushed pushed;
        try {
            pushed = engine.accept(data);
        } catch (IllegalArgumentException ex) {
            return badRequest("the policy data cannot be used by this server");
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.put("revision", pushed.revision());
        answer.put("accepted", pushed.accepted());
        answer.put("gate", pushed.gate());
        return ResponseEntity.ok(answer.toString());
    }

    private static ResponseEntity<String> badRequest(String message) {
        ObjectNode error = JSON.createObjectNode();
        error.put("error", "invalid_request");
        error.put("message", message);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(error.toString());
    }
}
