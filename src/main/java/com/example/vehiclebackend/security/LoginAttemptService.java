package com.example.vehiclebackend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for the login endpoint: after {@code max-attempts}
 * failures for the same username inside a sliding window, further attempts are
 * refused for {@code lockout-minutes} — regardless of whether the password
 * would have been right. A successful login (or any password change) clears the
 * record.
 *
 * <p>Keyed by username, lower-cased so "Admin" and "admin" share a bucket, and
 * checked <em>before</em> the account is looked up: an unknown username locks
 * exactly like a real one, so the endpoint stays free of user enumeration —
 * which is the one property the login path already guaranteed.
 *
 * <p>Deliberately <em>not</em> keyed by IP. Every browser request arrives through
 * Caddy → nginx, so the address comes from a client-supplied {@code X-Forwarded-For}
 * chain an attacker can rotate at will, while a proxy that stops forwarding it
 * would collapse every user onto one bucket and lock the whole fleet out.
 *
 * <p>Two accepted trade-offs, both bounded by the lockout expiring on its own:
 * state is per-instance and lost on restart (keeps the login path free of DB
 * writes; an attacker cannot restart the app), and someone who knows a username
 * can deliberately lock it — the account owner gets in again by waiting out the
 * window or by using "Passwort vergessen", which resets the counter.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Above this many tracked usernames, expired records are swept before inserting. */
    private static final int SWEEP_THRESHOLD = 10_000;

    /** {@code failures} since {@code windowStart}; {@code lockedUntil} is null while unlocked. */
    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {}

    private final Map<String, Attempts> byUsername = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration window;
    private final Duration lockout;
    private final Clock clock;

    @Autowired
    public LoginAttemptService(@Value("${security.login.max-attempts:5}") int maxAttempts,
                               @Value("${security.login.window-minutes:15}") long windowMinutes,
                               @Value("${security.login.lockout-minutes:15}") long lockoutMinutes) {
        this(maxAttempts, windowMinutes, lockoutMinutes, Clock.systemUTC());
    }

    LoginAttemptService(int maxAttempts, long windowMinutes, long lockoutMinutes, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
        this.lockout = Duration.ofMinutes(lockoutMinutes);
        this.clock = clock;
    }

    /** Off entirely when max-attempts is 0 or less — the documented escape hatch. */
    private boolean disabled() {
        return maxAttempts <= 0;
    }

    /**
     * @throws LoginLockedException if this username is currently locked out.
     */
    public void assertNotLocked(String username) {
        if (disabled() || username == null) {
            return;
        }
        Attempts attempts = byUsername.get(key(username));
        if (attempts == null || attempts.lockedUntil() == null) {
            return;
        }
        Duration remaining = Duration.between(clock.instant(), attempts.lockedUntil());
        if (remaining.isNegative() || remaining.isZero()) {
            return; // Expired; recordFailure/recordSuccess will drop the record.
        }
        throw new LoginLockedException(remaining);
    }

    /** Counts one failed attempt, starting the lockout once the threshold is hit. */
    public void recordFailure(String username) {
        if (disabled() || username == null) {
            return;
        }
        Instant now = clock.instant();
        if (byUsername.size() >= SWEEP_THRESHOLD) {
            sweepExpired(now);
        }
        String key = key(username);
        Attempts updated = byUsername.compute(key, (k, current) -> {
            // Start a fresh window when there is no live record, when the previous
            // window has gone quiet, or when an expired lockout is being reused.
            if (current == null || isStale(current, now)) {
                return new Attempts(1, now, null);
            }
            int failures = current.failures() + 1;
            Instant lockedUntil = failures >= maxAttempts ? now.plus(lockout) : current.lockedUntil();
            return new Attempts(failures, current.windowStart(), lockedUntil);
        });
        if (updated.failures() == maxAttempts) {
            log.warn("Login locked for '{}' after {} failed attempts; retry in {} min",
                    key, maxAttempts, lockout.toMinutes());
        }
    }

    /** Clears the record after a successful login. */
    public void recordSuccess(String username) {
        reset(username);
    }

    /** Clears the record — also called when a password is changed or reset, so a
     *  locked-out user who resets their password can log in immediately. */
    public void reset(String username) {
        if (username != null) {
            byUsername.remove(key(username));
        }
    }

    /** A record no longer worth keeping: lockout served, or window elapsed. */
    private boolean isStale(Attempts attempts, Instant now) {
        if (attempts.lockedUntil() != null) {
            return !attempts.lockedUntil().isAfter(now);
        }
        return attempts.windowStart().plus(window).isBefore(now);
    }

    private void sweepExpired(Instant now) {
        byUsername.values().removeIf(attempts -> isStale(attempts, now));
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
