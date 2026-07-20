package com.example.vehiclebackend.service;

import com.example.vehiclebackend.entity.PasswordResetToken;
import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.repository.PasswordResetTokenRepository;
import com.example.vehiclebackend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Self-service password reset. "Passwort vergessen" issues a single-use, expiring
 * token and emails a link to the account's address; the reset endpoint consumes it.
 *
 * <p>Email is optional infrastructure: if no SMTP host is configured the link is
 * logged instead of sent, so the app still boots and the flow is usable in dev.
 * {@link #requestReset} never reveals whether an account exists (no enumeration).
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String mailHost;
    private final String mailFrom;
    private final String frontendUrl;
    private final long ttlMinutes;

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordEncoder passwordEncoder,
                                ObjectProvider<JavaMailSender> mailSenderProvider,
                                @Value("${spring.mail.host:}") String mailHost,
                                @Value("${app.mail.from:no-reply@localhost}") String mailFrom,
                                @Value("${app.frontend-url:https://vehiclebackend.duckdns.org}") String frontendUrl,
                                @Value("${app.password-reset.ttl-minutes:60}") long ttlMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSenderProvider = mailSenderProvider;
        this.mailHost = mailHost;
        this.mailFrom = mailFrom;
        this.frontendUrl = frontendUrl;
        this.ttlMinutes = ttlMinutes;
    }

    /** Issue a reset token for the account with this email and send the link. Does
     *  nothing (silently) when no account matches — the caller always returns 204. */
    @Transactional
    public void requestReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            // One live token per user: drop any earlier ones so old links stop working.
            tokenRepository.deleteByUser(user);
            String token = newToken();
            tokenRepository.save(new PasswordResetToken(token, user,
                    Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES)));
            sendResetLink(user.getEmail(), buildLink(token));
        });
    }

    /** Consume a token and set the new password. Rejects unknown/used/expired tokens. */
    @Transactional
    public void reset(String token, String newPassword) {
        PasswordResetToken prt = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid or expired reset link"));
        if (prt.isUsed() || prt.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset link");
        }
        User user = prt.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        prt.setUsed(true);
        tokenRepository.save(prt);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildLink(String token) {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return base + "/?reset=" + token;
    }

    private void sendResetLink(String to, String link) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (mailHost.isBlank() || sender == null) {
            // No SMTP configured — log the link so the flow is still usable in dev.
            log.warn("Mail not configured; password-reset link for {}: {}", to, link);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setFrom(mailFrom);
        message.setSubject("Passwort zurücksetzen – BTZFahrzeuge");
        message.setText("Zum Zurücksetzen Ihres Passworts öffnen Sie diesen Link:\n\n" + link
                + "\n\nDer Link ist " + ttlMinutes + " Minuten gültig. Wenn Sie das nicht angefordert "
                + "haben, ignorieren Sie diese E-Mail.");
        try {
            sender.send(message);
        } catch (MailException e) {
            // Swallow so the request still returns 204 (no enumeration, no 500 on a
            // transient mail outage). The user simply won't get a mail this time.
            log.error("Failed to send password-reset email to {}", to, e);
        }
    }
}
