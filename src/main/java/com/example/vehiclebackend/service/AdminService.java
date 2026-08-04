package com.example.vehiclebackend.service;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.repository.BookingRecordRepository;
import com.example.vehiclebackend.repository.PasswordResetTokenRepository;
import com.example.vehiclebackend.repository.UserRepository;
import com.example.vehiclebackend.repository.VehicleRepository;
import com.example.vehiclebackend.security.LoginAttemptService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final BookingRecordRepository bookingRecordRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;

    public AdminService(UserRepository userRepository,
                        VehicleRepository vehicleRepository,
                        BookingRecordRepository bookingRecordRepository,
                        PasswordResetTokenRepository tokenRepository,
                        PasswordEncoder passwordEncoder,
                        LoginAttemptService loginAttempts) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.bookingRecordRepository = bookingRecordRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User createUser(String username, String password, String email) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail != null && userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }
        User user = new User(username, passwordEncoder.encode(password), "ROLE_USER");
        user.setEmail(normalizedEmail);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Concurrent create raced past the checks above; a unique constraint caught it.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email already in use");
        }
    }

    /** Admin-set a user's email (used as the reset-link target). Blank clears it. */
    public User setEmail(Long id, String email) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail != null) {
            userRepository.findByEmail(normalizedEmail)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
                    });
        }
        user.setEmail(normalizedEmail);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase();
    }

    /** Admin-set a user's password directly — no current-password check (unlike
     *  self-service change). Works for any account, including other admins. */
    @Transactional
    public User resetPassword(Long id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Any emailed reset link is now stale. Left alive it would stay usable for the
        // rest of its TTL, letting whoever holds it undo this password change.
        tokenRepository.deleteByUser(user);
        // An admin reset is also how a user who got locked out gets back in, so the
        // brute-force counter must not outlive the password it was protecting.
        loginAttempts.reset(user.getUsername());
        return userRepository.save(user);
    }

    @Transactional
    public User changeRole(Long id, String role) {
        if (!"ROLE_USER".equals(role) && !"ROLE_ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        // Never let the last admin be demoted — that would lock everyone out of
        // user management with no way back in through the app.
        if ("ROLE_ADMIN".equals(user.getRole()) && "ROLE_USER".equals(role)
                && userRepository.countByRole("ROLE_ADMIN") <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot demote the last admin");
        }
        user.setRole(role);
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if ("ROLE_ADMIN".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete admin users");
        }
        // Release any vehicles the user still has checked out, and close the trip that
        // went with each. Only toggleInUse's check-in path ever closes a BookingRecord,
        // so skipping this leaves a trip open forever: the Fahrtenbuch shows a vehicle
        // that was never returned, and the still-open record keeps blocking odometer
        // corrections and vehicle deletion long after the driver is gone.
        Instant now = Instant.now();
        List<Vehicle> checkedOut = vehicleRepository.findAllByInUseBy(user);
        for (Vehicle vehicle : checkedOut) {
            bookingRecordRepository
                    .findFirstByVehicleAndCheckedInAtIsNullOrderByCheckedOutAtDesc(vehicle)
                    .ifPresent(open -> {
                        open.setCheckedInAt(now);
                        open.setKmAtCheckin(vehicle.getKilometers());
                        bookingRecordRepository.save(open);
                    });
            vehicle.setInUse(false);
            vehicle.setInUseBy(null);
            vehicle.setInUseSince(null);
        }
        vehicleRepository.saveAll(checkedOut);
        // vehicles.user_id is NOT NULL, so deleting a creator would violate the FK.
        if (vehicleRepository.existsByUser(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "User still owns vehicles — delete or reassign them first");
        }
        // password_reset_tokens FKs the user — clear any before deleting.
        tokenRepository.deleteByUser(user);
        // Don't leave a lockout behind for a name that could be handed out again.
        loginAttempts.reset(user.getUsername());
        userRepository.delete(user);
    }
}
