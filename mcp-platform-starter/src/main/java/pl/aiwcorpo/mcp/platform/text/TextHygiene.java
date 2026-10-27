package pl.aiwcorpo.mcp.platform.text;

/**
 * Characters that make text mean something other than it shows, for two uses with one definition:
 * {@link #normalize} cleans text that comes from a backend system before it goes to the model or into a log,
 * {@link #firstProblem} judges text that comes from the model (tool arguments).
 *
 * <p>What is dangerous: C0/C1 controls and DEL (forge log lines and terminal output), U+2028/U+2029 (line
 * breaks that many log viewers and JSON consumers treat differently), unpaired surrogates and noncharacters
 * (invalid text, parsed differently by different consumers), and format characters ({@code \p{Cf}}): bidi
 * overrides and isolates that reorder what a reviewer reads, zero-width characters and U+2060..U+2064 that hide
 * text from a human but not from a model, tag characters U+E0000..U+E007F that carry an invisible message.
 * Format characters are an allowlist in free text ({@link #allowedInFreeText}), not a denylist.
 */
public final class TextHygiene {

    /** What is wrong with a piece of text. */
    public enum Problem {
        /** C0 (other than tab, newline and carriage return), DEL, C1. */
        CONTROL("control characters"),
        /** Tab, newline or carriage return where the value is a single formatted token. */
        LINE_BREAK("line breaks or tabs"),
        /** U+2028 or U+2029. */
        LINE_SEPARATOR("line or paragraph separators"),
        /** An unpaired UTF-16 surrogate or a Unicode noncharacter. */
        INVALID_TEXT("characters that are not valid text"),
        /** Bidi controls, zero-width characters, tag characters and the like. */
        INVISIBLE("invisible or direction-changing characters");

        private final String description;

        Problem(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private TextHygiene() {
    }

    /**
     * Text from a system, made safe to show: control characters, unpaired surrogates, noncharacters and every
     * {@code \p{Cf}} character are dropped (a zero-width joiner in an emoji sequence goes too: the sequence
     * degrades to separate emoji, which is the cost of not letting invisible characters through); U+2028, U+2029
     * and U+0085 become a newline, and a carriage return becomes a newline (CRLF one newline). Tab and newline
     * stay. {@code null} gives the empty string.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            int point = points[i];
            if (point == '\r') {
                if (i + 1 < points.length && points[i + 1] == '\n') {
                    continue;
                }
                out.append('\n');
            } else if (point == 0x2028 || point == 0x2029 || point == 0x85) {
                out.append('\n');
            } else if (point == '\n' || point == '\t') {
                out.append((char) point);
            } else if (isControl(point) || isInvalid(point) || Character.getType(point) == Character.FORMAT) {
                continue;
            } else {
                out.appendCodePoint(point);
            }
        }
        return out.toString();
    }

    /**
     * The first problem in text received from the model, or {@code null}.
     *
     * @param formatted {@code true} for a value that has a {@code pattern} (a key, a path, a name): there a
     *                  newline, tab or carriage return and every format character are a problem too; in free
     *                  text newline, carriage return and tab are normal and only the format characters that hide
     *                  or reorder text are refused (only U+200C..U+200F stay, because emoji sequences and
     *                  right-to-left scripts need them)
     */
    public static Problem firstProblem(String text, boolean formatted) {
        int length = text.length();
        for (int i = 0; i < length; ) {
            int point = text.codePointAt(i);
            i += Character.charCount(point);
            boolean whitespace = point == '\n' || point == '\r' || point == '\t';
            if (whitespace) {
                if (formatted) {
                    return Problem.LINE_BREAK;
                }
            } else if (isControl(point)) {
                return Problem.CONTROL;
            } else if (point == 0x2028 || point == 0x2029) {
                return Problem.LINE_SEPARATOR;
            } else if (isInvalid(point)) {
                return Problem.INVALID_TEXT;
            } else if (Character.getType(point) == Character.FORMAT && (formatted || !allowedInFreeText(point))) {
                return Problem.INVISIBLE;
            }
        }
        return null;
    }

    private static boolean isControl(int point) {
        return Character.isISOControl(point) && point != '\n' && point != '\t' && point != '\r';
    }

    /** An unpaired surrogate (a pair is read as one code point) or a noncharacter. */
    private static boolean isInvalid(int point) {
        return (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE)
                || (point >= 0xFDD0 && point <= 0xFDEF) || (point & 0xFFFE) == 0xFFFE;
    }

    /**
     * The only format characters ({@code \p{Cf}}) free text may keep: ZWNJ and ZWJ (U+200C, U+200D), which Persian,
     * Indic scripts and emoji sequences need, and the LRM and RLM marks (U+200E, U+200F), which fix the direction of
     * punctuation in mixed-direction text and move nothing beyond their neighbours. Everything else in the category
     * is refused, not listed: soft hyphen U+00AD, Arabic letter mark U+061C, the other bidi controls, zero-width
     * space U+200B, word joiner and invisible operators U+2060..U+2064, interlinear annotation U+FFF9..U+FFFB, BOM,
     * tag characters, and whatever Unicode adds to the category later.
     */
    private static boolean allowedInFreeText(int point) {
        return point == 0x200C || point == 0x200D || point == 0x200E || point == 0x200F;
    }
}
