package com.example.vehiclebackend.config;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.admin.username:admin}")
    private String adminUsername;
    @Value("${seed.admin.password:}")
    private String adminPassword;
    @Value("${seed.demo.username:demo}")
    private String demoUsername;
    @Value("${seed.demo.password:}")
    private String demoPassword;

    public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public CommandLineRunner seedUsers() {
        return args -> {
            // Normalize any legacy demo user with a missing / non-ROLE_ prefixed role.
            userRepository.findByUsername(demoUsername).ifPresent(u -> {
                if (u.getRole() == null || !u.getRole().startsWith("ROLE_")) {
                    u.setRole("ROLE_USER");
                    userRepository.save(u);
                }
            });

            // Accounts are only seeded when a password is explicitly configured.
            // No credentials are ever hardcoded, so a public deployment that omits
            // these env vars ships without a default admin backdoor.
            seedIfAbsent(demoUsername, demoPassword, "ROLE_USER");
            seedIfAbsent(adminUsername, adminPassword, "ROLE_ADMIN");
        };
    }

    private void seedIfAbsent(String username, String password, String role) {
        if (password == null || password.isBlank()) {
            log.info("No password configured for '{}' — skipping seed", username);
            return;
        }
        if (userRepository.findByUsername(username).isEmpty()) {
            userRepository.save(new User(username, passwordEncoder.encode(password), role));
            log.info("Seeded {} user: {}", role, username);
        }
    }
}
