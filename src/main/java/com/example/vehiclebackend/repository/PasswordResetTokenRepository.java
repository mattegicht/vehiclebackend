package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.PasswordResetToken;
import com.example.vehiclebackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    void deleteByUser(User user);
}
