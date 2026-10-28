package pl.aiwcorpo.mcp.platform.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What the platform can follow in a result and in the arguments of a tool. Anything it cannot follow stops the server:
 * a result it cannot see into, or an annotation it would silently ignore, would hand the model resources the policy
 * never judged.
 */
class ResourceRefsTest {

    public record Hit(@ResourceRef(type = "Jira::Issue") String key, String summary) {
    }

    public record Page<T>(List<T> items, int total) {
    }

    public record Renamed(@JsonProperty("issues") List<Hit> hits) {
    }

    public record Plain(String id, List<String> names) {
    }

    public record Wrapper(String name, Optional<List<Map<String, Update>>> deep) {
    }

    public static class PojoArgument {
        public String name;
        public List<Update> updates;
    }

    public static class Pojo {
        public List<Hit> hits;
    }

    public record Update(@ResourceRef(type = "Jira::Issue") String key, String summary) {
    }

    @SuppressWarnings("unused")
    static class Tools {
        public Page<Hit> generic() {
            return null;
        }

        public Optional<List<Hit>> optional() {
            return null;
        }

        public Hit[][] nested() {
            return null;
        }

        public String text() {
            return null;
        }

        public Plain plain() {
            return null;
        }

        public Map<String, Hit> map() {
            return null;
        }

        @ResultWithoutResources
        public Map<String, String> declaredMap() {
            return null;
        }

        public Map<String, String> scalarMap() {
            return null;
        }

        public Pojo pojo() {
            return null;
        }

        public JsonNode json() {
            return null;
        }

        public Object anything() {
            return null;
        }

        @ResultWithoutResources
        public JsonNode declaredJson() {
            return null;
        }

        @ResultWithoutResources
        public Page<Hit> declaredButHasResources() {
            return null;
        }

        public Renamed renamed() {
            return null;
        }

        public String updateIssue(Update request) {
            return null;
        }

        public String updateMany(List<Update> requests) {
            return null;
        }

        public String updateOptional(Optional<Update> request) {
            return null;
        }

        public String updateMap(Map<String, Update> requests) {
            return null;
        }

        public String updateNestedOptionalInRecord(Wrapper wrapper) {
            return null;
        }

        public String updatePojo(PojoArgument argument) {
            return null;
        }

        public String twoResolvers(@ResourceRef(type = "Jira::Issue", resolver = String.class) String a,
                                   @ResourceRef(type = "Jira::Issue", resolver = Integer.class) String b) {
            return null;
        }

        public String badArgument(@ResourceRef(type = "Jira::Issue") Update notAnIdentifier) {
            return null;
        }

        public String badType(@ResourceRef(type = "Jira::Nothing") String id) {
            return null;
        }
    }

    private static Method method(String name) {
        for (Method method : Tools.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError(name);
    }

    @Test
    void genericRecordsOptionalsAndArraysAreFollowed() {
        ResourceRefs.ResultShape page = ResourceRefs.result(method("generic"));
        ResourceRefs.ResultShape optional = ResourceRefs.result(method("optional"));
        ResourceRefs.ResultShape nested = ResourceRefs.result(method("nested"));

        assertThat(page).isInstanceOfSatisfying(ResourceRefs.Fields.class, fields -> {
            assertThat(fields.fields()).containsOnlyKeys("items");
            assertThat(fields.fields().get("items")).isInstanceOfSatisfying(ResourceRefs.Many.class,
                    many -> assertThat(many.element()).isInstanceOf(ResourceRefs.Element.class));
            assertThat(fields.known()).containsExactlyInAnyOrder("items", "total");
        });
        assertThat(optional).isInstanceOf(ResourceRefs.Many.class);
        assertThat(nested).isInstanceOfSatisfying(ResourceRefs.Many.class,
                many -> assertThat(many.element()).isInstanceOf(ResourceRefs.Many.class));
    }

    @Test
    void resultsWithoutResourcesNeedNothing() {
        for (String name : new String[] {"text", "plain", "scalarMap"}) {
            assertThat(ResourceRefs.result(method(name))).as(name).isInstanceOf(ResourceRefs.None.class);
        }
    }

    @Test
    void aResultThePlatformCannotSeeIntoStopsTheServerUnlessDeclared() {
        for (String name : new String[] {"map", "pojo", "json", "anything"}) {
            assertThatThrownBy(() -> ResourceRefs.result(method(name))).as(name).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@ResultWithoutResources").hasMessageContaining(name);
        }
        assertThat(ResourceRefs.result(method("declaredJson"))).isInstanceOf(ResourceRefs.None.class);
        assertThat(ResourceRefs.result(method("declaredMap"))).isInstanceOf(ResourceRefs.None.class);
    }

    @Test
    void declaringNoResourcesOnAResultThatHasThemIsAContradiction() {
        assertThatThrownBy(() -> ResourceRefs.result(method("declaredButHasResources")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("remove one of them");
    }

    @Test
    void aJacksonAnnotationOnARecordThatHoldsResourcesStopsTheServer() {
        assertThatThrownBy(() -> ResourceRefs.result(method("renamed"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@JsonProperty").hasMessageContaining("hide resources");
    }

    @Test
    void aResourceRefBelowTheTopLevelOfAnArgumentStopsTheServer() {
        for (String name : new String[] {"updateIssue", "updateMany", "updateOptional", "updateMap", "updateNestedOptionalInRecord",
                "updatePojo"}) {
            assertThatThrownBy(() -> ResourceRefs.arguments(method(name))).as(name).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not on a component of an argument record");
        }
    }

    @Test
    void anArgumentThatIsNotAnIdentifierOrNamesAnUnknownTypeOrTwoResolversIsRefused() {
        assertThatThrownBy(() -> ResourceRefs.arguments(method("badArgument"))).hasMessageContaining("@ResourceRef");
        assertThatThrownBy(() -> ResourceRefs.arguments(method("badType"))).hasMessageContaining("Jira::Nothing");
        assertThatThrownBy(() -> ResourceRefs.arguments(method("twoResolvers"))).hasMessageContaining("different resolvers");
    }
}
