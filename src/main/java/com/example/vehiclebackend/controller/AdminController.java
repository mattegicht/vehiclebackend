package com.example.vehiclebackend.controller;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.service.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    record CreateUserRequest(@NotBlank String username, @NotBlank @Size(min = 8) String password,
                             @Email String email) {}
    record ResetPasswordRequest(@NotBlank @Size(min = 8) String newPassword) {}
    record ChangeRoleRequest(@NotBlank String role) {}
    record SetEmailRequest(@Email String email) {}
    record UserResponse(Long id, String username, String role, String email) {}

    private static UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getRole(), u.getEmail());
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(adminService.getAllUsers().stream()
                .map(AdminController::toResponse)
                .toList());
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest req) {
        User user = adminService.createUser(req.username(), req.password(), req.email());
        return ResponseEntity.ok(toResponse(user));
    }

    @PutMapping("/users/{id}/email")
    public ResponseEntity<UserResponse> setEmail(@PathVariable Long id,
                                                 @Valid @RequestBody SetEmailRequest req) {
        return ResponseEntity.ok(toResponse(adminService.setEmail(id, req.email())));
    }

    @PutMapping("/users/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                              @Valid @RequestBody ResetPasswordRequest req) {
        adminService.resetPassword(id, req.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<UserResponse> changeRole(@PathVariable Long id,
                                                   @Valid @RequestBody ChangeRoleRequest req) {
        User user = adminService.changeRole(id, req.role());
        return ResponseEntity.ok(toResponse(user));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
