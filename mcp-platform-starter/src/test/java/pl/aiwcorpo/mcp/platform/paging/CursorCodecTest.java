package pl.aiwcorpo.mcp.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.auth.TestCallers;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;

class CursorCodecTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final String FP = CursorCodec.fingerprint("project = ABC", "created");

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final CursorCodec codec = new CursorCodec(KEY, Duration.ofMinutes(15), clock);

    @Test
    void aCursorReturnsThePositionToTheCallerItWasIssuedTo() {
        String cursor = codec.issueFor("user-1", "search_issues", FP, "nextPageToken=Zm9v/+ ą");

        assertThat(codec.openFor("user-1", cursor, "search_issues", FP)).isEqualTo("nextPageToken=Zm9v/+ ą");
    }

    @Test
    void theCursorUsesOnlyTheCharactersTheArgumentPatternAllows() {
        String cursor = codec.issueFor("user-1", "search_issues", FP, "x".repeat(CursorCodec.MAX_STATE_BYTES));

        assertThat(cursor).matches(CursorCodec.ARGUMENT_PATTERN);
    }

    @Test
    void theCurrentCallerIsTheSubjectInsideARequest() {
        String cursor = TestCallers.as(TestCallers.user("search_issues"), () -> codec.issue("search_issues", FP, "42"));

        assertThat(TestCallers.as(TestCallers.user("search_issues"), () -> codec.open(cursor, "search_issues", FP)))
                .isEqualTo("42");
        assertThatThrownBy(() -> TestCallers.as(TestCallers.service(), () -> codec.open(cursor, "search_issues", FP)))
                .isInstanceOf(InvalidToolArgumentException.class);
    }

    @Test
    void outsideARequestThereIsNoSubject() {
        assertThatThrownBy(() -> codec.issue("search_issues", FP, "1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherUserAnotherToolOrAnotherQueryIsRefusedWithOneText() {
        String cursor = codec.issueFor("user-1", "search_issues", FP, "42");

        for (Runnable attempt : new Runnable[] {
                () -> codec.openFor("user-2", cursor, "search_issues", FP),
                () -> codec.openFor("user-1", cursor, "list_projects", FP),
                () -> codec.openFor("user-1", cursor, "search_issues", CursorCodec.fingerprint("project = DEF", "created"))}) {
            assertThatThrownBy(attempt::run).isInstanceOf(InvalidToolArgumentException.class)
                    .hasMessage(CursorCodec.REFUSED_MESSAGE);
        }
    }

    @Test
    void anExpiredCursorIsRefused() {
        String cursor = codec.issueFor("user-1", "search_issues", FP, "42");

        now.set(now.get().plusSeconds(15 * 60 - 1));
        assertThat(codec.openFor("user-1", cursor, "search_issues", FP)).isEqualTo("42");
        now.set(now.get().plusSeconds(1));
        assertThatThrownBy(() -> codec.openFor("user-1", cursor, "search_issues", FP))
                .isInstanceOf(InvalidToolArgumentException.class).hasMessage(CursorCodec.REFUSED_MESSAGE);
    }

    @Test
    void aCursorSignedWithAnotherKeyIsRefused() {
        CursorCodec other = new CursorCodec("ffffffffffffffffffffffffffffffff".getBytes(), Duration.ofMinutes(15), clock);
        String cursor = other.issueFor("user-1", "search_issues", FP, "42");

        assertThatThrownBy(() -> codec.openFor("user-1", cursor, "search_issues", FP))
                .isInstanceOf(InvalidToolArgumentException.class);
    }

    @Test
    void forgedTruncatedAndMalformedCursorsAreRefused() {
        String cursor = codec.issueFor("user-1", "search_issues", FP, "42");
        String[] parts = cursor.split("\\.");
        String flipped = parts[0].substring(0, parts[0].length() - 1) + (parts[0].endsWith("A") ? "B" : "A");

        for (String bad : new String[] {null, "", ".", "abc", flipped + "." + parts[1], parts[0] + ".", "." + parts[1],
                parts[0] + "." + parts[1] + ".x", parts[0] + "." + parts[1].substring(1), "!!!.???",
                "x".repeat(CursorCodec.MAX_CURSOR_LENGTH + 1)}) {
            assertThatThrownBy(() -> codec.openFor("user-1", bad, "search_issues", FP))
                    .as(String.valueOf(bad)).isInstanceOf(InvalidToolArgumentException.class)
                    .hasMessage(CursorCodec.REFUSED_MESSAGE);
        }
    }

    @Test
    void aPositionLongerThanTheLimitCannotBeIssued() {
        assertThatThrownBy(() -> codec.issueFor("user-1", "t", FP, "x".repeat(CursorCodec.MAX_STATE_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFingerprintSeparatesPartsAndTellsNullFromEmpty() {
        assertThat(CursorCodec.fingerprint("ab", "c")).isNotEqualTo(CursorCodec.fingerprint("a", "bc"));
        assertThat(CursorCodec.fingerprint("a", null)).isNotEqualTo(CursorCodec.fingerprint("a", ""));
        assertThat(CursorCodec.fingerprint("a", "b")).isEqualTo(CursorCodec.fingerprint("a", "b"));
    }

    @Test
    void aCursorOfAnotherServerIsRefusedEvenWithTheSameKey() {
        CursorCodec jira = new CursorCodec(KEY, Duration.ofMinutes(15), clock, "mcp-jira");
        CursorCodec gitlab = new CursorCodec(KEY, Duration.ofMinutes(15), clock, "mcp-gitlab");
        String cursor = jira.issueFor("user-1", "search", FP, "42");

        assertThat(jira.openFor("user-1", cursor, "search", FP)).isEqualTo("42");
        assertThatThrownBy(() -> gitlab.openFor("user-1", cursor, "search", FP))
                .isInstanceOf(InvalidToolArgumentException.class).hasMessage(CursorCodec.REFUSED_MESSAGE);
    }

    @Test
    void aChangedSignatureByteOrAnotherSpellingOfTheSameBytesIsRefused() {
        String cursor = codec.issueFor("user-1", "search_issues", FP, "42");
        String[] parts = cursor.split("\\.");
        String signature = parts[1];
        char last = signature.charAt(signature.length() - 1);
        // 32 bytes leave four unused bits in the last character: the decoder reads these spellings as the same signature.
        String sameBytesOtherSpelling = parts[0] + "." + signature.substring(0, signature.length() - 1)
                + (last == 'A' ? 'B' : 'A');
        String changedFirstByte = parts[0] + "." + (signature.charAt(0) == 'A' ? 'B' : 'A') + signature.substring(1);

        for (String bad : new String[] {sameBytesOtherSpelling, changedFirstByte, parts[0] + "=." + signature,
                parts[0] + "." + signature + "="}) {
            assertThatThrownBy(() -> codec.openFor("user-1", bad, "search_issues", FP)).as(bad)
                    .isInstanceOf(InvalidToolArgumentException.class).hasMessage(CursorCodec.REFUSED_MESSAGE);
        }
    }

    @Test
    void aMissingToolOrFingerprintIsAProgrammingErrorNotARefusedCursor() {
        String cursor = codec.issueFor("user-1", "search_issues", FP, "42");

        assertThatThrownBy(() -> codec.openFor("user-1", cursor, null, FP)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.issueFor("user-1", "t", null, "1")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aGeneratedKeyWarnsOnTheFirstCursorOnly() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(CursorCodec.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines =
                new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            CursorCodec generated = CursorCodec.withGeneratedKey(KEY, Duration.ofMinutes(1), clock, "mcp-jira");
            assertThat(lines.list).isEmpty();
            generated.issueFor("user-1", "t", FP, "1");
            generated.issueFor("user-1", "t", FP, "2");
            assertThat(lines.list).hasSize(1);
        } finally {
            logger.detachAppender(lines);
        }
    }

    @Test
    void aShortKeyAndANonPositiveValidityAreRefusedAtConstruction() {
        assertThatThrownBy(() -> new CursorCodec(new byte[31], Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorCodec(KEY, Duration.ZERO, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
