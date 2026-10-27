package pl.aiwcorpo.mcp.platform.text;

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
 * macros, mentions) and pass the filtered result on; the factories also cut the text to a limit. Fields a system adds next to the four
 * required ones (for example {@code omittedMacros}) go in the surrounding result record.
 *
 * @param kind      always {@code untrusted-data}
 * @param format    {@code text/plain} or {@code text/markdown}; the server converts richer formats (storage
 *                  format, wiki markup, HTML) to one of them
 * @param text      the text
 * @param truncated whether the text was cut to the limit
 */
public record UntrustedText(String kind, String format, String text, boolean truncated) {

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

    /** Plain text, normalized, cut to at most {@code maxLength} UTF-16 units (never inside a surrogate pair). */
    public static UntrustedText plain(String raw, int maxLength) {
        return of(PLAIN, raw, maxLength);
    }

    /** Markdown, normalized and cut like {@link #plain}. */
    public static UntrustedText markdown(String raw, int maxLength) {
        return of(MARKDOWN, raw, maxLength);
    }

    private static UntrustedText of(String format, String raw, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative");
        }
        String clean = TextHygiene.normalize(raw);
        if (clean.length() <= maxLength) {
            return new UntrustedText(KIND, format, clean, false);
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(clean.charAt(end - 1))) {
            end--;
        }
        return new UntrustedText(KIND, format, clean.substring(0, end), true);
    }
}
