package com.example.vehiclebackend.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain JUnit — the service holds no Spring or DB state, so time is the only thing to fake. */
class LoginAttemptServiceTest {

    /** Hand-wound clock: the lockout is all about elapsed time. */
    private static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-04T10:00:00Z");

        void advance(Duration amount) { now = now.plus(amount); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private final TestClock clock = new TestClock();
    /** 3 strikes, 15-minute window, 10-minute lockout. */
    private final LoginAttemptService service = new LoginAttemptService(3, 15, 10, clock);

    private void fail(String username, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(username);
        }
    }

    @Test
    void allowsAttemptsBelowTheThreshold() {
        fail("anna", 2);
        assertDoesNotThrow(() -> service.assertNotLocked("anna"));
    }

    @Test
    void locksAfterThresholdAndReportsRemainingTime() {
        fail("anna", 3);
        LoginLockedException e = assertThrows(LoginLockedException.class, () -> service.assertNotLocked("anna"));
        assertEquals(600, e.getRetryAfterSeconds());
    }

    @Test
    void lockoutIsPerUsernameOnly() {
        fail("anna", 3);
        assertDoesNotThrow(() -> service.assertNotLocked("bert"));
    }

    @Test
    void usernameKeyIsCaseInsensitiveSoCaseVariationsCannotBypassIt() {
        fail("anna", 2);
        fail("ANNA", 1);
        assertThrows(LoginLockedException.class, () -> service.assertNotLocked("Anna"));
    }

    @Test
    void lockoutExpiresAndStartsACleanCount() {
        fail("anna", 3);
        clock.advance(Duration.ofMinutes(10));
        assertDoesNotThrow(() -> service.assertNotLocked("anna"));

        // The expired record must not leave the account one strike from re-locking.
        fail("anna", 2);
        assertDoesNotThrow(() -> service.assertNotLocked("anna"));
        fail("anna", 1);
        assertThrows(LoginLockedException.class, () -> service.assertNotLocked("anna"));
    }

    @Test
    void failuresOlderThanTheWindowDoNotAccumulate() {
        fail("anna", 2);
        clock.advance(Duration.ofMinutes(16));
        fail("anna", 2);
        assertDoesNotThrow(() -> service.assertNotLocked("anna"));
    }

    @Test
    void successfulLoginClearsTheCount() {
        fail("anna", 2);
        service.recordSuccess("anna");
        fail("anna", 2);
        assertDoesNotThrow(() -> service.assertNotLocked("anna"));
    }

    @Test
    void resetUnlocksALockedAccount() {
        fail("anna", 3);
        service.reset("anna");
        assertDoesNotThrow(() -> service.assertNotLocked("anna"));
    }

    @Test
    void retryAfterShrinksAsTheLockoutRunsDown() {
        fail("anna", 3);
        clock.advance(Duration.ofMinutes(9));
        LoginLockedException e = assertThrows(LoginLockedException.class, () -> service.assertNotLocked("anna"));
        assertTrue(e.getRetryAfterSeconds() <= 60, "expected <= 60s left, got " + e.getRetryAfterSeconds());
        assertTrue(e.getRetryAfterSeconds() >= 1);
    }

    @Test
    void maxAttemptsZeroDisablesTheLockout() {
        LoginAttemptService disabled = new LoginAttemptService(0, 15, 10, clock);
        for (int i = 0; i < 50; i++) {
            disabled.recordFailure("anna");
        }
        assertDoesNotThrow(() -> disabled.assertNotLocked("anna"));
    }
}
