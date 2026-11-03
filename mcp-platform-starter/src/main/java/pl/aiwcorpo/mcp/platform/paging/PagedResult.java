package pl.aiwcorpo.mcp.platform.paging;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The shape of a result that can be longer than one call returns, the same in every server: the page, the cursor of
 * the next one, whether the server itself cut the content, and what it left out for a technical reason.
 *
 * <ul>
 *   <li>{@code nextCursor} comes from {@link CursorCodec}; {@code null} means the backend has nothing more. A page can
 *       be empty or short and still have a {@code nextCursor}: elements the {@code ResultFilter} removed leave no
 *       trace, so only the cursor tells whether to go on.</li>
 *   <li>{@code truncated} is true when the server cut content at its own limit (a log, a diff, a page body), not
 *       when there is a next page.</li>
 *   <li>{@code omitted} lists, as fixed snake case codes ({@code binary_attachment}, {@code unsupported_macro}), kinds of
 *       content that were left out because the server cannot render them safely. It never counts or names elements
 *       removed by the policy: a number of hidden elements would tell the caller that they exist.</li>
 * </ul>
 *
 * <p>There is deliberately no {@code total}: a count from the backend includes what the caller may not see, and the
 * platform does not correct it.
 *
 * @param <T> the element; a record with {@code @ResourceRef} components is checked by the platform as in any list
 */
public record PagedResult<T>(List<T> items, @Nullable String nextCursor, boolean truncated, List<String> omitted) {

    /** Most distinct reasons a result can list. */
    public static final int MAX_OMITTED = 10;

    private static final Pattern OMITTED_CODE = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    public PagedResult {
        items = List.copyOf(items);
        omitted = List.copyOf(omitted);
        if (nextCursor != null && nextCursor.length() > CursorCodec.MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("nextCursor is longer than " + CursorCodec.MAX_CURSOR_LENGTH);
        }
        if (omitted.size() > MAX_OMITTED || omitted.stream().distinct().count() != omitted.size()) {
            throw new IllegalArgumentException("omitted lists at most " + MAX_OMITTED + " distinct codes");
        }
        for (String code : omitted) {
            if (!OMITTED_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("omitted code '" + code + "' is not snake case of at most 40 characters");
            }
        }
    }

    /** The last page, nothing cut and nothing left out. */
    public static <T> PagedResult<T> of(List<T> items, @Nullable String nextCursor) {
        return new PagedResult<>(items, nextCursor, false, List.of());
    }
}
