package pl.aiwcorpo.mcp.platform.text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;

/**
 * Free text that came from a backend system (page body, comment, ticket description, title, log line) in the
 * shape the contracts define as {@code UntrustedText} (CC-072, {@code common.schema.json}): the client sees that
 * it is data and not an instruction. A tool that returns it in its result record (directly or in a list or a
 * nested record) gets an {@code outputSchema} with this shape published by the platform, so the shape is part
 * of the tool's {@code definitionHash}; see {@link ToolOutputSchemas}.
 *
 * <p>The constructor normalizes the text ({@link TextHygiene#normalize}), so a value carries no control, bidi or
 * zero-width characters however it was built (a factory, {@code new}, Jackson). Normalization replaces what it
 * removes with a space, so a server must normalize the raw text <em>before</em> it filters it (links, images,
 * macros, mentions) and pass the filtered result on; the factories also cut the text to a limit, at a word boundary. Fields a system adds next to the four
 * required ones (for example {@code omittedMacros}) go in the surrounding result record.
 *
 * @param kind      always {@code untrusted-data}
 * @param format    {@code text/plain} or {@code text/markdown}; the server converts richer formats (storage
 *                  format, wiki markup, HTML) to one of them
 * @param text      the text
 * @param truncated whether the text was cut to the limit
 */
public record UntrustedText(String kind, String format, String text, boolean truncated) {

    private static final Logger LOG = LoggerFactory.getLogger(UntrustedText.class);
    private static final int WORD_LIMIT = 64;

    public static final String KIND = "untrusted-data";
    public static final String PLAIN = "text/plain";
    public static final String MARKDOWN = "text/markdown";

    public UntrustedText {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("kind must be " + KIND);
        }
        if (!PLAIN.equals(format) && !MARKDOWN.equals(format)) {
            throw new IllegalArgumentException("format must be " + PLAIN + " or " + MARKDOWN);
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        text = TextHygiene.normalize(text);
    }

    /** Plain text, normalized, cut to at most {@code maxLength} UTF-16 units (see {@link #cut}). */
    public static UntrustedText plain(String raw, int maxLength) {
        return of(PLAIN, raw, maxLength);
    }

    /** Markdown, normalized and cut like {@link #plain}. */
    public static UntrustedText markdown(String raw, int maxLength) {
        return of(MARKDOWN, raw, maxLength);
    }

    /**
     * A cut inside a word would make a new token at the end of the text that the server's filter never saw
     * ({@code "hello @allison"} cut to 10 units is {@code "hello @all"}). So the cut moves back to the last white
     * space when the part of the last word it would keep is at most {@value #WORD_LIMIT} units; a longer unbroken
     * run (text in a script written without spaces, a blob) is cut where the limit falls, because dropping it would
     * lose the text and it is no mention or macro name. The cut never splits a surrogate pair.
     *
     * @return the text to keep, without trailing white space; shorter than {@code text} exactly when something was cut
     */
    static String cut(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && end < text.length() && !Character.isWhitespace(text.charAt(end))
                && !Character.isWhitespace(text.charAt(end - 1))) {
            int start = end;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
            if (end - start <= WORD_LIMIT) {
                end = start;
            }
        }
        return text.substring(0, end).stripTrailing();
    }

    private static UntrustedText of(String format, String raw, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative");
        }
        String clean = TextHygiene.normalize(raw);
        if (!clean.equals(raw)) {
            // The text changed here, after the server's own filter had looked at it: the filter saw something else
            // than the client will, typically because entities were decoded after normalize() (MIGRATION-3.0.md).
            LOG.warn("event=untrusted_text_not_normalized tool={} detail=\"normalize() before the filter, then the "
                    + "factory\"", CallerContext.current().map(CallerContext::tool).map(TextHygiene::normalize).orElse("-"));
        }
        String kept = cut(clean, maxLength);
        return new UntrustedText(KIND, format, kept, kept.length() < clean.length());
    }
}
