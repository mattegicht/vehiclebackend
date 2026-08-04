package com.example.vehiclebackend.controller;

import com.example.vehiclebackend.security.LoginLockedException;
import com.example.vehiclebackend.service.AuthService;
import com.example.vehiclebackend.service.PasswordResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    record LoginResponse(String token, String username, String role) {}
    record ForgotPasswordRequest(@NotBlank @Email String email) {}
    record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 8) String newPassword) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            AuthService.LoginResult result = authService.login(req.username(), req.password());
            return ResponseEntity.ok(new LoginResponse(result.token(), req.username(), result.role()));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid credentials");
        } catch (LoginLockedException e) {
            // 429 + Retry-After: the client renders its own wait message from the header.
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                    .body("Too many failed login attempts");
        }
    }

    // Always 204, whether or not the email matches an account (no user enumeration).
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordResetService.requestReset(req.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.reset(req.token(), req.newPassword());
        return ResponseEntity.noContent().build();
    }
}
