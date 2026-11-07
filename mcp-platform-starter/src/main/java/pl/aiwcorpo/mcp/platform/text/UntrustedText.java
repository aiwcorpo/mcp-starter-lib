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
 * macros, mentions) and pass the filtered result on; the factories also cut the text to a limit ({@link #cut}). A server
 * that filters should cut first, then filter, and build the value with {@link #plainCut}. Fields a system adds next to the four
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
     * Text a server has already cut with {@link #cut} and filtered afterwards: normalized by the constructor, not cut
     * again.
     *
     * @param truncated whether {@link #cut} (or the server) removed something
     */
    public static UntrustedText plainCut(String text, boolean truncated) {
        return new UntrustedText(KIND, PLAIN, text, truncated);
    }

    /** Markdown the server has already cut and filtered, like {@link #plainCut}. */
    public static UntrustedText markdownCut(String text, boolean truncated) {
        return new UntrustedText(KIND, MARKDOWN, text, truncated);
    }

    /**
     * Cuts normalized text to at most {@code maxLength} UTF-16 units so that the cut makes no token the server's
     * filter has not seen. The server cuts <em>before</em> its filter and passes the result to
     * {@link #plainCut}/{@link #markdownCut}: only a filter that runs on the cut text sees what the client sees
     * (a cut can also remove the closing delimiter of a code span or block, which turns {@code `@all`}, harmless
     * as code, into a plain {@code @all}).
     *
     * <p>A cut inside a word would make a new token at the end of the text ({@code "hello @allison"} cut to 10
     * units is {@code "hello @all"}). So the cut moves back to the last white space when the part of the last
     * word it would keep is at most {@value #WORD_LIMIT} units and white space precedes it. Otherwise (the word
     * starts the text, or it is longer: text in a script written without spaces, a blob,
     * {@code "((((...(@allison"}) the cut drops the word characters at its end ({@code [\p{L}\p{N}\p{M}_.-]}: a
     * name such as {@code all-hands} or {@code a.b} is one word), so no name or scheme is left half written after a
     * delimiter ({@code "/@all-hands"} becomes {@code "/@"}, not {@code "/@all-"}). When that would drop more than
     * {@value #WORD_LIMIT} units, or the text up to the limit has no delimiter at all, the cut stays where the limit
     * falls: a long run is not a short name for a token to complete, and without a delimiter there is no token start.
     * A single word longer than the limit therefore keeps its first {@code maxLength} units
     * ({@code truncated} is true) instead of becoming empty. The cut never splits a surrogate pair.
     *
     * @return the text to keep, without trailing white space; shorter than {@code text} exactly when something was cut
     * @throws IllegalArgumentException when {@code maxLength} is negative
     */
    public static String cut(String text, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative");
        }
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
            if (start > 0 && end - start <= WORD_LIMIT) {
                end = start;
            } else {
                int word = withoutWordTail(text, start, end);
                if (word != end) {
                    end = word;
                } else {
                    // Staying at the limit must not leave a short name followed by a run of punctuation ("@all"
                    // plus seventy dots): in the original that name ends with a letter, here a renderer that
                    // trims trailing punctuation from a mention would see "@all". Drop the run, then judge the
                    // name that is left like any other tail.
                    int trimmed = end;
                    while (trimmed > start && isNamePunctuation(Character.codePointBefore(text, trimmed))) {
                        trimmed -= Character.charCount(Character.codePointBefore(text, trimmed));
                    }
                    if (trimmed > start && trimmed < end) {
                        end = withoutWordTail(text, start, trimmed);
                    }
                }
            }
        }
        return text.substring(0, end).stripTrailing();
    }

    /** {@code end} moved back over the word characters at the end of {@code text[start, end)} when that is at most {@value #WORD_LIMIT} units and not all of it. */
    private static int withoutWordTail(String text, int start, int end) {
        int word = end;
        while (word > start && isWordCharacter(Character.codePointBefore(text, word))) {
            word -= Character.charCount(Character.codePointBefore(text, word));
        }
        return word > start && end - word <= WORD_LIMIT ? word : end;
    }

    /** Word characters that are neither a letter nor a digit: they can join a name but a name does not end with them. */
    private static boolean isNamePunctuation(int point) {
        return isWordCharacter(point) && !Character.isLetterOrDigit(point);
    }

    private static boolean isWordCharacter(int point) {
        if (Character.isLetterOrDigit(point) || point == '_' || point == '-' || point == '.') {
            return true;
        }
        int type = Character.getType(point);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static UntrustedText of(String format, String raw, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative");
        }
        String clean = TextHygiene.normalize(raw);
        if (raw != null && !clean.equals(raw)) {
            // The text changed here, after the server's own filter had looked at it: the filter saw something else
            // than the client will, typically because entities were decoded after normalize().
            LOG.warn("event=untrusted_text_not_normalized tool={} detail=\"normalize() before the filter, then the "
                    + "factory\"", CallerContext.current().map(CallerContext::tool).map(TextHygiene::normalize).orElse("-"));
        }
        String kept = cut(clean, maxLength);
        return new UntrustedText(KIND, format, kept, kept.length() < clean.length());
    }
}
