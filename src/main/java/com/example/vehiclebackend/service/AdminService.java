package com.example.vehiclebackend.service;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.repository.UserRepository;
import com.example.vehiclebackend.repository.VehicleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminService(UserRepository userRepository,
                        VehicleRepository vehicleRepository,
                        PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User createUser(String username, String password) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        try {
            return userRepository.save(new User(username, passwordEncoder.encode(password), "ROLE_USER"));
        } catch (DataIntegrityViolationException e) {
            // Concurrent create raced past the check above; the unique constraint caught it.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
    }

    /** Admin-set a user's password directly — no current-password check (unlike
     *  self-service change). Works for any account, including other admins. */
    public User resetPassword(Long id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
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
        // Release any vehicles the user still has checked out.
        List<Vehicle> checkedOut = vehicleRepository.findAllByInUseBy(user);
        for (Vehicle vehicle : checkedOut) {
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
        userRepository.delete(user);
    }
}
