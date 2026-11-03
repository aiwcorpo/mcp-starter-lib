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
    void aShortKeyAndANonPositiveValidityAreRefusedAtConstruction() {
        assertThatThrownBy(() -> new CursorCodec(new byte[31], Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorCodec(KEY, Duration.ZERO, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
