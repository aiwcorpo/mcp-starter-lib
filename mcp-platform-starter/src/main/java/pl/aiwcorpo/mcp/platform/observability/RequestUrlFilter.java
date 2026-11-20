package pl.aiwcorpo.mcp.platform.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * Spring Boot records the full URL as {@code http.url} on server and client spans. For a client call a server makes through Boot's
 * {@code RestClient.Builder} that URL carries the query string, which holds tool arguments (a search expression, an issue key), so only
 * the route or URI template stays on the span.
 */
public final class RequestUrlFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        context.removeHighCardinalityKeyValue("http.url");
        return context;
    }
}
