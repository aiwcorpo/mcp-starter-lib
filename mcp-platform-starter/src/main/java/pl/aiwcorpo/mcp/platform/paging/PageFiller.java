package pl.aiwcorpo.mcp.platform.paging;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Fills a page to the size the client asked for, reading further pages of the backend, so that a page the
 * {@code ResultFilter} thinned out does not tell the caller how many elements were hidden: a short page with a
 * {@code nextCursor}, or a run of empty ones, would.
 *
 * <p>The loop reads at most {@code maxFetches} backend pages. If it stops there with fewer than {@code minimumItems}
 * and the backend has more, {@link Filled#exhausted()} is true: the result is what was found and the position to go on
 * from, and the caller can still see a short page. The only remaining signal is therefore bounded by the number of reads
 * the server allows itself; it is not removed. A page can hold more than {@code minimumItems} (the last backend page is
 * taken whole, because the position of the backend is a page boundary).
 */
public final class PageFiller {

    /** Hard cap on {@code maxFetches}: each read is a call to the system for one request. */
    public static final int MAX_FETCHES_LIMIT = 20;

    private PageFiller() {
    }

    /**
     * One page of the backend.
     *
     * @param items the elements as the system returned them
     * @param next  the position of the following page; {@code null} when there is none
     */
    public record BackendPage<B>(List<B> items, @Nullable String next) {
        public BackendPage {
            items = List.copyOf(items);
        }
    }

    /**
     * @param items     the allowed elements, in the order of the system
     * @param next      the position to put in the cursor; {@code null} when the backend has nothing more
     * @param exhausted the read limit ended the loop before the page was full
     */
    public record Filled<T>(List<T> items, @Nullable String next, boolean exhausted) {
        public Filled {
            items = List.copyOf(items);
        }
    }

    /**
     * @param start          position from the cursor; {@code null} for the first page
     * @param minimumItems   the size the client asked for (at least 1)
     * @param maxFetches     most backend reads for this call (1 to {@value #MAX_FETCHES_LIMIT})
     * @param fetch          reads the page of the backend at a position
     * @param allowed        removes what the caller may not see, for example through {@code ResultFilter#retainAllowedById}
     */
    public static <B, T> Filled<T> fill(@Nullable String start, int minimumItems, int maxFetches,
                                        Function<@Nullable String, BackendPage<B>> fetch,
                                        Function<List<B>, List<T>> allowed) {
        if (minimumItems < 1) {
            throw new IllegalArgumentException("minimumItems must be at least 1");
        }
        if (maxFetches < 1 || maxFetches > MAX_FETCHES_LIMIT) {
            throw new IllegalArgumentException("maxFetches must be between 1 and " + MAX_FETCHES_LIMIT);
        }
        List<T> collected = new ArrayList<>();
        String position = start;
        for (int reads = 1; ; reads++) {
            BackendPage<B> page = fetch.apply(position);
            collected.addAll(allowed.apply(page.items()));
            position = page.next();
            if (position == null) {
                return new Filled<>(collected, null, false);
            }
            if (collected.size() >= minimumItems) {
                return new Filled<>(collected, position, false);
            }
            if (reads >= maxFetches) {
                return new Filled<>(collected, position, true);
            }
        }
    }
}
