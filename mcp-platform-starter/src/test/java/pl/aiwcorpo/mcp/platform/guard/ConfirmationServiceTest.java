package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.aiwcorpo.mcp.platform.guard.ConfirmationService.Result.CONFIRMED;
import static pl.aiwcorpo.mcp.platform.guard.ConfirmationService.Result.EXPIRED;
import static pl.aiwcorpo.mcp.platform.guard.ConfirmationService.Result.LOCKED;
import static pl.aiwcorpo.mcp.platform.guard.ConfirmationService.Result.NO_CHALLENGE;
import static pl.aiwcorpo.mcp.platform.guard.ConfirmationService.Result.WRONG_CODE;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfirmationServiceTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final CallerIdentity karol = new CallerIdentity("karol", true, RiskLevel.DESTRUCTIVE, Set.of(), Set.of());
    private final CallerIdentity anna = new CallerIdentity("anna", true, RiskLevel.DESTRUCTIVE, Set.of(), Set.of());

    private Instant now = Instant.parse("2026-10-04T00:00:00Z");
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
            return now;
        }
    };

    private String sentCode;
    private final ConfirmationService service =
            new ConfirmationService((identity, tool, summary, code, expiresAt) -> sentCode = code, clock);

    private void issue(CallerIdentity who, String tool, String args) {
        service.issue(who, tool, args, "summary", TTL, 3);
    }

    @Test
    void theCodeLooksLikeTheOneOnTheDiagramAndWorksOnce() {
        issue(karol, "repo_purge", "args-A");

        assertThat(sentCode).matches("\\d{3}-\\d{3}");
        assertThat(service.verify(karol, "repo_purge", "args-A", sentCode)).isEqualTo(CONFIRMED);
        assertThat(service.verify(karol, "repo_purge", "args-A", sentCode))
                .as("single use").isEqualTo(NO_CHALLENGE);
    }

    @Test
    void theCodeIsUselessForAnyOtherCall() {
        issue(karol, "repo_purge", "args-A");

        assertThat(service.verify(karol, "repo_purge", "args-B", sentCode))
                .as("different arguments").isEqualTo(NO_CHALLENGE);
        assertThat(service.verify(karol, "db_drop", "args-A", sentCode))
                .as("different tool").isEqualTo(NO_CHALLENGE);
        assertThat(service.verify(anna, "repo_purge", "args-A", sentCode))
                .as("different caller").isEqualTo(NO_CHALLENGE);
        assertThat(service.verify(karol, "repo_purge", "args-A", sentCode))
                .as("none of those attempts consumed it").isEqualTo(CONFIRMED);
    }

    @Test
    void acceptsTheCodeHoweverThePersonTypedIt() {
        issue(karol, "repo_purge", "args-A");
        String digits = sentCode.replace("-", "");

        assertThat(service.verify(karol, "repo_purge", "args-A", " " + digits.substring(0, 3) + " " + digits.substring(3)))
                .isEqualTo(CONFIRMED);
    }

    @Test
    void wrongCodesLockTheChallenge() {
        issue(karol, "repo_purge", "args-A");
        String wrong = sentCode.startsWith("0") ? "111-111" : "000-000";

        assertThat(service.verify(karol, "repo_purge", "args-A", wrong)).isEqualTo(WRONG_CODE);
        assertThat(service.verify(karol, "repo_purge", "args-A", wrong)).isEqualTo(WRONG_CODE);
        assertThat(service.verify(karol, "repo_purge", "args-A", null)).isEqualTo(LOCKED);
        assertThat(service.verify(karol, "repo_purge", "args-A", sentCode))
                .as("even the right code is dead after the lock").isEqualTo(LOCKED);
    }

    @Test
    void askingForANewCodeDoesNotBuyNewGuesses() {
        String wrong = "not-a-code";
        for (int guess = 0; guess < 3; guess++) {
            issue(karol, "repo_purge", "args-A");                 // a fresh challenge before every guess
            service.verify(karol, "repo_purge", "args-A", wrong);
        }

        assertThatThrownBy(() -> issue(karol, "repo_purge", "args-A"))
                .as("three wrong codes in the window: no more codes are sent")
                .isInstanceOf(ConfirmationService.ThrottledException.class);
        issue(anna, "repo_purge", "args-A");                      // someone else is unaffected
        assertThat(service.verify(anna, "repo_purge", "args-A", sentCode)).isEqualTo(CONFIRMED);

        now = now.plus(TTL);                                      // the window ends
        issue(karol, "repo_purge", "args-A");
        assertThat(service.verify(karol, "repo_purge", "args-A", sentCode)).isEqualTo(CONFIRMED);
    }

    @Test
    void aCallerCannotFloodTheApproverWithCodes() {
        for (int i = 0; i < ConfirmationService.MAX_ISSUED_PER_WINDOW; i++) {
            issue(karol, "repo_purge", "args-" + i);
        }
        assertThatThrownBy(() -> issue(karol, "repo_purge", "one-too-many"))
                .isInstanceOf(ConfirmationService.ThrottledException.class);
    }

    @Test
    void aCancelledChallengeIsGone() {
        issue(karol, "repo_purge", "args-A");
        service.cancel(karol, "repo_purge", "args-A");

        assertThat(service.verify(karol, "repo_purge", "args-A", sentCode)).isEqualTo(NO_CHALLENGE);
    }

    @Test
    void theCodeExpires() {
        issue(karol, "repo_purge", "args-A");

        now = now.plus(TTL);
        assertThat(service.verify(karol, "repo_purge", "args-A", sentCode)).isEqualTo(EXPIRED);
    }

    @Test
    void aNewChallengeReplacesTheOldCode() {
        issue(karol, "repo_purge", "args-A");
        String first = sentCode;
        String second = first;
        for (int i = 0; i < 20 && second.equals(first); i++) {   // a 1-in-a-million repeat must not fail the build
            issue(karol, "repo_purge", "args-A");
            second = sentCode;
        }

        assertThat(service.verify(karol, "repo_purge", "args-A", first)).isEqualTo(WRONG_CODE);
        assertThat(service.verify(karol, "repo_purge", "args-A", second)).isEqualTo(CONFIRMED);
    }
}
