package pl.aiwcorpo.mcp.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.paging.PageFiller.BackendPage;

class PageFillerTest {

    private final List<String> reads = new ArrayList<>();

    /** Backend pages by position: the first one holds only hidden elements ("h..."). */
    private final Map<String, BackendPage<String>> backend = Map.of(
            "", new BackendPage<>(List.of("h1", "h2"), "p1"),
            "p1", new BackendPage<>(List.of("h3", "a1"), "p2"),
            "p2", new BackendPage<>(List.of("a2", "h4"), "p3"),
            "p3", new BackendPage<>(List.of("a3"), null));

    private BackendPage<String> fetch(String position) {
        String key = position == null ? "" : position;
        reads.add(key);
        return backend.get(key);
    }

    private static List<String> visible(List<String> items) {
        return items.stream().filter(item -> item.startsWith("a")).toList();
    }

    @Test
    void aPageThinnedOutByTheFilterIsFilledFromTheFollowingPages() {
        PageFiller.Filled<String> page = PageFiller.fill(null, 2, 5, this::fetch, PageFillerTest::visible);

        assertThat(page.items()).containsExactly("a1", "a2");
        assertThat(page.next()).isEqualTo("p3");
        assertThat(page.exhausted()).isFalse();
        assertThat(reads).containsExactly("", "p1", "p2");
    }

    @Test
    void theLastPageEndsTheLoopWithoutACursor() {
        PageFiller.Filled<String> page = PageFiller.fill("p3", 5, 5, this::fetch, PageFillerTest::visible);

        assertThat(page.items()).containsExactly("a3");
        assertThat(page.next()).isNull();
        assertThat(page.exhausted()).isFalse();
    }

    @Test
    void theReadLimitEndsALoopWhoseFilterRemovedEverythingAndSaysSo() {
        PageFiller.Filled<String> page = PageFiller.fill(null, 2, 1, this::fetch, PageFillerTest::visible);

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isEqualTo("p1");
        assertThat(page.exhausted()).isTrue();
        assertThat(reads).hasSize(1);
    }

    @Test
    void theArgumentsAreChecked() {
        Function<List<String>, List<String>> all = Function.identity();
        assertThatThrownBy(() -> PageFiller.fill(null, 0, 1, this::fetch, all)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageFiller.fill(null, 1, 0, this::fetch, all)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageFiller.fill(null, 1, PageFiller.MAX_FETCHES_LIMIT + 1, this::fetch, all))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
