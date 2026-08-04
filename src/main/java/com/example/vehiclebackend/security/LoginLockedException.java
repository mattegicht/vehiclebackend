package com.example.vehiclebackend.security;

import java.time.Duration;

/**
 * Thrown instead of attempting authentication while a username is locked out
 * after too many failed logins. Carries how long the caller has to wait so the
 * endpoint can answer with a {@code Retry-After} header.
 */
public class LoginLockedException extends RuntimeException {

    private final Duration retryAfter;

    public LoginLockedException(Duration retryAfter) {
        super("Too many failed login attempts");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** Rounded up, and never below 1 — a {@code Retry-After: 0} invites an instant retry. */
    public long getRetryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
