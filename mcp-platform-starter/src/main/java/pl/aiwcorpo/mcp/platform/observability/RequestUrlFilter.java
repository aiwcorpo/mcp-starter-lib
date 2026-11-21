package pl.aiwcorpo.mcp.platform.observability;

import java.util.Set;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.transport.SenderContext;

/**
 * Spring Boot records the full URL as {@code http.url} on server and client spans. For a client call a server makes through Boot's
 * {@code RestClient.Builder} that URL carries the query string, which holds tool arguments (a search expression, an issue key), so only
 * the route or URI template stays on the span. {@link UriLabel} keeps the {@code uri} tag free of a query and of credentials: a caller
 * that concatenates the URL passes it to {@code RestClient} as a "template", and the tag would repeat the query.
 * For a call to another system (a client observation) the tag is kept only when it contains a template variable: a path built by
 * concatenation (an issue key, a project path) has none, and is as telling as a query.
 */
public final class RequestUrlFilter implements ObservationFilter {

    private static final Set<String> URL_KEYS = Set.of("http.url", "url.full", "http.target", "url.query", "url.path");

    @Override
    public Observation.Context map(Observation.Context context) {
        URL_KEYS.forEach(context::removeHighCardinalityKeyValue);
        URL_KEYS.forEach(context::removeLowCardinalityKeyValue);
        KeyValue uri = context.getLowCardinalityKeyValues().stream().filter(kv -> "uri".equals(kv.getKey())).findFirst().orElse(null);
        if (uri != null) {
            String label = UriLabel.of(uri.getValue());
            if (context instanceof SenderContext<?> && !label.contains("{") && !"none".equals(label)) {
                label = UriLabel.UNKNOWN;
            }
            if (!uri.getValue().equals(label)) {
                context.addLowCardinalityKeyValue(KeyValue.of("uri", label));
            }
        }
        return context;
    }
}
