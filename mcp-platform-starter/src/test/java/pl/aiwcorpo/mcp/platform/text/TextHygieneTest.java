package pl.aiwcorpo.mcp.platform.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.text.TextHygiene.Problem;

class TextHygieneTest {

    @Test
    void textFromASystemLosesWhatHidesOrReorders() {
        assertThat(TextHygiene.normalize("a\u200bb\u2060c\ufeffd\u202ee\u2066f\udb40\udc41g")).isEqualTo("abcdefg");
        assertThat(TextHygiene.normalize("one\u2028two\u2029three\u0085four")).isEqualTo("one\ntwo\nthree\nfour");
        assertThat(TextHygiene.normalize("x\r\ny\rz\u0007\u001b[31m\u007f\u0090")).isEqualTo("x\ny\nz[31m");
        assertThat(TextHygiene.normalize("lone\ud800 and\uffff and\ufdd0")).isEqualTo("lone and and");
        assertThat(TextHygiene.normalize("tab\tkept, naive caf\u00e9 \ud83d\ude00 kept")).isEqualTo("tab\tkept, naive caf\u00e9 \ud83d\ude00 kept");
        assertThat(TextHygiene.normalize(null)).isEmpty();
    }

    @Test
    void textFromTheModelIsJudgedByWhereItWillGo() {
        assertThat(TextHygiene.firstProblem("plain\nmulti\tline\r\ntext \u200d\u200f", false)).isNull();
        assertThat(TextHygiene.firstProblem("a\u0007", false)).isEqualTo(Problem.CONTROL);
        assertThat(TextHygiene.firstProblem("a\u2028", false)).isEqualTo(Problem.LINE_SEPARATOR);
        assertThat(TextHygiene.firstProblem("a\u202e", false)).isEqualTo(Problem.INVISIBLE);
        assertThat(TextHygiene.firstProblem("a\ud800", false)).isEqualTo(Problem.INVALID_TEXT);
        assertThat(TextHygiene.firstProblem("a\uffff", false)).isEqualTo(Problem.INVALID_TEXT);
        assertThat(TextHygiene.firstProblem("a\nb", true)).isEqualTo(Problem.LINE_BREAK);
        assertThat(TextHygiene.firstProblem("a\u200db", true)).isEqualTo(Problem.INVISIBLE);
    }

    @Test
    void untrustedTextIsNormalizedAndCutOnACodePointBoundary() {
        UntrustedText text = UntrustedText.plain("ab\u200bc\ud83d\ude00de", 4);

        assertThat(text.text()).isEqualTo("abc");
        assertThat(text.truncated()).isTrue();
        assertThat(text.kind()).isEqualTo("untrusted-data");
        assertThat(text.format()).isEqualTo("text/plain");
        assertThat(UntrustedText.markdown("short", 100)).isEqualTo(new UntrustedText("untrusted-data", "text/markdown", "short", false));
    }

    @Test
    void theShapeCannotBeForged() {
        assertThatThrownBy(() -> new UntrustedText("instructions", "text/plain", "x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UntrustedText("untrusted-data", "text/html", "x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
