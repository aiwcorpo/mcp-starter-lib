package pl.aiwcorpo.mcp.platform.text;

/**
 * The text hygiene rules of the contracts (CC-121, CC-122; {@code test-vectors/text-hygiene/}), one definition for two
 * uses: {@link #firstProblem} judges text that comes from the model (tool arguments), {@link #normalize} cleans text
 * that comes from a backend system before it goes to the model or into a log.
 *
 * <p>The forbidden set: C0 controls other than TAB, LF and CR; DEL and C1 controls; U+2028 and U+2029; unpaired
 * surrogates; noncharacters (U+FDD0..U+FDEF, U+xFFFE, U+xFFFF); every {@code \p{Cf}} character except U+200C..U+200F
 * (ZWNJ, ZWJ, LRM, RLM, which emoji sequences and right-to-left scripts need); the invisible characters U+034F,
 * U+115F, U+1160, U+3164, U+FFA0 and U+2800; the variation selectors U+FE00..U+FE0F and U+E0100..U+E01EF, except one
 * U+FE0F directly after an {@code Extended_Pictographic} character (an emoji presentation selector).
 *
 * <p>There is no Unicode normalization (no NFKC, no NFC): text passes unchanged except for the set above. Full width
 * ASCII (U+FF01..U+FF5E) is refused only in arguments that reach a mention filter, because only there "@all" written
 * with full width letters means something to the system.
 */
public final class TextHygiene {

    /** What is wrong with a piece of text. */
    public enum Problem {
        /** C0 (other than tab, newline and carriage return), DEL, C1. */
        CONTROL("control_character", "control characters"),
        /** Tab, newline or carriage return where the value is a single formatted token. */
        LINE_BREAK("line_break_in_identifier", "line breaks or tabs"),
        /** U+2028 or U+2029. */
        LINE_SEPARATOR("line_separator", "line or paragraph separators"),
        /** An unpaired UTF-16 surrogate. */
        UNPAIRED_SURROGATE("unpaired_surrogate", "characters that are not valid text"),
        /** A Unicode noncharacter. */
        NONCHARACTER("noncharacter", "characters that are not valid text"),
        /** A variation selector that is not an emoji presentation selector. */
        VARIATION_SELECTOR("variation_selector", "invisible or direction-changing characters"),
        /** U+034F, Hangul fillers, Braille blank. */
        INVISIBLE_CHARACTER("invisible_character", "invisible or direction-changing characters"),
        /** Bidi controls, zero-width characters, tag characters and the like. */
        FORMAT_CHARACTER("format_character", "invisible or direction-changing characters"),
        /** U+FF01..U+FF5E in an argument that reaches a mention filter. */
        FULL_WIDTH_ASCII("full_width_ascii", "full width characters");

        private final String reason;
        private final String description;

        Problem(String reason, String description) {
            this.reason = reason;
            this.description = description;
        }

        /** The reason as the contract's vectors name it (never sent to a client). */
        public String reason() {
            return reason;
        }

        /** Wording for the {@code invalid_argument} message: names the kind of characters, never the value. */
        public String description() {
            return description;
        }
    }

    private TextHygiene() {
    }

    /**
     * Text from a system, made safe to show: every maximal run of forbidden characters becomes one space, nothing is
     * trimmed and nothing else changes (CC-121). Tab, newline and carriage return stay, as do U+200C..U+200F and an
     * emoji presentation selector. {@code null} gives the empty string.
     *
     * <p>The run becomes a space, not nothing: with nothing, {@code "@a<ZWSP>ll"} would turn into {@code "@all"} and
     * {@code "!<ZWSP>https://host/x.png!"} into image syntax, that is, text a server's own filter let through because
     * it did not recognise it would come out as the very token it looks for. A zero-width joiner inside an emoji
     * sequence is allowed, so emoji sequences stay whole.
     *
     * <p>Run this on the raw text <em>before</em> any server-side filtering (removing links, images, macros,
     * mentions), not after it: the filter has to see the text the client will see.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        int[] points = text.codePoints().toArray();
        StringBuilder out = new StringBuilder(text.length());
        boolean inRun = false;
        for (int i = 0; i < points.length; i++) {
            if (problemAt(points, i, false, false) != null) {
                if (!inRun) {
                    out.append(' ');
                }
                inRun = true;
            } else {
                inRun = false;
                out.appendCodePoint(points[i]);
            }
        }
        return out.toString();
    }

    /**
     * The first problem in a tool argument, or {@code null}.
     *
     * @param identifier {@code true} for a value that has a {@code pattern} (a key, a path, a name): there tab, newline
     *                   and carriage return are forbidden too; in free text they are ordinary
     */
    public static Problem firstProblem(String text, boolean identifier) {
        return firstProblem(text, identifier, false);
    }

    /**
     * As {@link #firstProblem(String, boolean)}; {@code mentionFiltered} adds the full width ASCII characters
     * U+FF01..U+FF5E, for an argument the server passes on to a system that interprets mentions.
     */
    public static Problem firstProblem(String text, boolean identifier, boolean mentionFiltered) {
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            Problem problem = problemAt(points, i, identifier, mentionFiltered);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static Problem problemAt(int[] points, int index, boolean identifier, boolean mentionFiltered) {
        int point = points[index];
        if (point < 0x20) {
            if (point == '\t' || point == '\n' || point == '\r') {
                return identifier ? Problem.LINE_BREAK : null;
            }
            return Problem.CONTROL;
        }
        if (point >= 0x7F && point <= 0x9F) {
            return Problem.CONTROL;
        }
        if (point == 0x2028 || point == 0x2029) {
            return Problem.LINE_SEPARATOR;
        }
        if (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE) {
            return Problem.UNPAIRED_SURROGATE;
        }
        if ((point >= 0xFDD0 && point <= 0xFDEF) || (point & 0xFFFE) == 0xFFFE) {
            return Problem.NONCHARACTER;
        }
        if (point == 0xFE0F && index > 0 && Character.isExtendedPictographic(points[index - 1])) {
            return null;
        }
        if ((point >= 0xFE00 && point <= 0xFE0F) || (point >= 0xE0100 && point <= 0xE01EF)) {
            return Problem.VARIATION_SELECTOR;
        }
        if (point == 0x034F || point == 0x115F || point == 0x1160 || point == 0x3164 || point == 0xFFA0
                || point == 0x2800) {
            return Problem.INVISIBLE_CHARACTER;
        }
        if (Character.getType(point) == Character.FORMAT && !(point >= 0x200C && point <= 0x200F)) {
            return Problem.FORMAT_CHARACTER;
        }
        if (mentionFiltered && point >= 0xFF01 && point <= 0xFF5E) {
            return Problem.FULL_WIDTH_ASCII;
        }
        return null;
    }
}
