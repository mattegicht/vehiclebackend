package com.example.vehiclebackend.controller;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    record ChangePasswordRequest(@NotBlank String currentPassword,
                                 @NotBlank @Size(min = 8) String newPassword) {}
    record EmailRequest(@Email String email) {}
    record MeResponse(String username, String role, String email) {}

    private User currentUser(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication auth) {
        User user = currentUser(auth);
        return ResponseEntity.ok(new MeResponse(user.getUsername(), user.getRole(), user.getEmail()));
    }

    /** Self-service: set/update (or clear, if blank) the current user's email — the
     *  address a "Passwort vergessen" link is sent to. */
    @PutMapping("/me/email")
    public ResponseEntity<MeResponse> setMyEmail(@Valid @RequestBody EmailRequest req, Authentication auth) {
        User user = currentUser(auth);
        String normalized = req.email() == null || req.email().isBlank()
                ? null : req.email().trim().toLowerCase();
        if (normalized != null) {
            userRepository.findByEmail(normalized)
                    .filter(other -> !other.getId().equals(user.getId()))
                    .ifPresent(other -> {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
                    });
        }
        user.setEmail(normalized);
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }
        return ResponseEntity.ok(new MeResponse(user.getUsername(), user.getRole(), user.getEmail()));
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                               Authentication auth) {
        User user = currentUser(auth);
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }
}
