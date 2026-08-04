package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);

    /** Case-insensitive lookup for the password-reset flow. Addresses are stored
     *  normalized, but rows written before that was enforced may still hold mixed
     *  case, and `=` on varchar is case-sensitive on PostgreSQL. */
    Optional<User> findByEmailIgnoreCase(String email);
    long countByRole(String role);
}
