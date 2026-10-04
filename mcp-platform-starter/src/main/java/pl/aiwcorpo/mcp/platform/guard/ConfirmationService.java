package pl.aiwcorpo.mcp.platform.guard;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and verifies the one-time codes that gate destructive calls.
 *
 * <p>Wrong guesses are counted per caller across challenges, so requesting a new code does not buy
 * new guesses; after {@code max-attempts} wrong codes the caller is locked out until the window
 * (one TTL) ends.
 *
 * <p>A code is bound to three things at once — the caller, the tool and the exact arguments — so a
 * code issued for "purge project A" cannot be spent on "purge project B", by someone else, or on
 * another tool. It is single-use, short-lived and attempt-limited, and only its hash is kept.
 *
 * <p>State is in memory: correct for one instance, which is how these servers run. Behind a load
 * balancer with several instances this needs a shared store (or sticky sessions).
 */
public class ConfirmationService {

    /** Outcome of {@link #verify}. Only {@link #CONFIRMED} lets the call through. */
    public enum Result { CONFIRMED, WRONG_CODE, LOCKED, EXPIRED, NO_CHALLENGE }

    /** The caller has used up their wrong guesses or their requests for codes; try again later. */
    public static class ThrottledException extends RuntimeException {

        private final transient Instant retryAt;

        ThrottledException(Instant retryAt) {
            super("confirmation throttled");
            this.retryAt = retryAt;
        }

        public Instant getRetryAt() {
            return retryAt;
        }
    }

    /**
     * Per-caller budget within one window. Without it the attempt limit on a single challenge means
     * nothing: an agent could request a fresh code after every wrong guess, walking the whole code
     * space and burying the approver in messages.
     */
    private record Budget(Instant windowEnd, int wrongCodes, int issued) {
    }

    /** Codes one caller may request per window, across all tools. */
    static final int MAX_ISSUED_PER_WINDOW = 10;

    /** What the caller is told about an issued challenge. Never contains the code. */
    public record Challenge(Instant expiresAt, int attemptsAllowed) {
    }

    private record Pending(byte[] codeHash, Instant expiresAt, int attemptsLeft) {
    }

    /** Upper bound on live challenges, so issuing them cannot be used to exhaust memory. */
    private static final int MAX_PENDING = 10_000;

    private final ConfirmationCodeSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();
    /** Wrong codes per window after which a caller is locked out; follows the policy's max-attempts. */
    private volatile int lockAfter = 3;

    public ConfirmationService(ConfirmationCodeSender sender) {
        this(sender, Clock.systemUTC());
    }

    public ConfirmationService(ConfirmationCodeSender sender, Clock clock) {
        this.sender = sender;
        this.clock = clock;
    }

    /**
     * Creates a fresh challenge for this exact call, replacing any earlier one, and sends the code
     * to the caller out-of-band.
     */
    public Challenge issue(CallerIdentity identity, String tool, String argsFingerprint, String summary,
                           Duration ttl, int maxAttempts) {
        Instant now = clock.instant();
        lockAfter = maxAttempts;
        Budget budget = budgets.compute(identity.subject(), (subject, current) -> {
            Budget live = current == null || !current.windowEnd().isAfter(now)
                    ? new Budget(now.plus(ttl), 0, 0) : current;
            return new Budget(live.windowEnd(), live.wrongCodes(), live.issued() + 1);
        });
        if (budget.wrongCodes() >= maxAttempts || budget.issued() > MAX_ISSUED_PER_WINDOW) {
            throw new ThrottledException(budget.windowEnd());
        }
        if (budgets.size() > MAX_PENDING) {
            budgets.values().removeIf(b -> !b.windowEnd().isAfter(now));
        }
        if (pending.size() >= MAX_PENDING) {
            pending.values().removeIf(p -> !p.expiresAt().isAfter(now));
            if (pending.size() >= MAX_PENDING) {
                throw new IllegalStateException("Too many pending confirmations");
            }
        }
        String code = newCode();
        Instant expiresAt = now.plus(ttl);
        pending.put(key(identity, tool, argsFingerprint), new Pending(hash(code), expiresAt, maxAttempts));
        sender.send(identity, tool, summary, code, expiresAt);
        return new Challenge(expiresAt, maxAttempts);
    }

    /** Checks a code against the challenge for this exact call. A correct code is consumed. */
    public Result verify(CallerIdentity identity, String tool, String argsFingerprint, String code) {
        String key = key(identity, tool, argsFingerprint);
        Instant now = clock.instant();
        Budget budget = budgets.get(identity.subject());
        if (budget != null && budget.windowEnd().isAfter(now) && budget.wrongCodes() >= Math.max(1, lockAfter)) {
            pending.remove(key);
            return Result.LOCKED;
        }
        Result[] result = {Result.NO_CHALLENGE};
        pending.compute(key, (k, current) -> {
            if (current == null) {
                return null;
            }
            if (!current.expiresAt().isAfter(clock.instant())) {
                result[0] = Result.EXPIRED;
                return null;
            }
            if (code != null && MessageDigest.isEqual(current.codeHash(), hash(normalize(code)))) {
                result[0] = Result.CONFIRMED;
                return null;                       // single use
            }
            int left = current.attemptsLeft() - 1;
            if (left <= 0) {
                result[0] = Result.LOCKED;
                return null;                       // burned: a new challenge must be issued
            }
            result[0] = Result.WRONG_CODE;
            return new Pending(current.codeHash(), current.expiresAt(), left);
        });
        if (result[0] == Result.WRONG_CODE || result[0] == Result.LOCKED) {
            budgets.computeIfPresent(identity.subject(), (subject, current) ->
                    new Budget(current.windowEnd(), current.wrongCodes() + 1, current.issued()));
        } else if (result[0] == Result.CONFIRMED) {
            budgets.computeIfPresent(identity.subject(), (subject, current) ->
                    new Budget(current.windowEnd(), 0, current.issued()));
        }
        return result[0];
    }

    /** Withdraws the challenge for this call, e.g. when the user declined. */
    public void cancel(CallerIdentity identity, String tool, String argsFingerprint) {
        pending.remove(key(identity, tool, argsFingerprint));
    }

    private String newCode() {
        int n = random.nextInt(1_000_000);
        return String.format("%03d-%03d", n / 1000, n % 1000);
    }

    /** People type "123 456" or "123456" for "123-456"; all three mean the same code. */
    private static String normalize(String code) {
        String digits = code.replaceAll("[^0-9]", "");
        return digits.length() == 6 ? digits.substring(0, 3) + "-" + digits.substring(3) : code.trim();
    }

    private static String key(CallerIdentity identity, String tool, String argsFingerprint) {
        return identity.subject() + '\u0000' + tool + '\u0000' + argsFingerprint;
    }

    private static byte[] hash(String code) {
        return GuardPolicy.sha256Hex(code).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
