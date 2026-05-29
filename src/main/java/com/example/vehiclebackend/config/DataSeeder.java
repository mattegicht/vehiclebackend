package com.example.vehiclebackend.config;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataSeeder {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public CommandLineRunner seedUsers() {
        return args -> {
            var demo = userRepository.findByUsername("demo");
            if (demo.isEmpty()) {
                userRepository.save(new User("demo", passwordEncoder.encode("password"), "ROLE_USER"));
                System.out.println("Seeded demo user: demo / password");
            } else {
                demo.ifPresent(u -> {
                    if (u.getRole() == null || !u.getRole().startsWith("ROLE_")) {
                        u.setRole("ROLE_USER");
                        userRepository.save(u);
                    }
                });
            }

            if (userRepository.findByUsername("admin").isEmpty()) {
                userRepository.save(new User("admin", passwordEncoder.encode("admin"), "ROLE_ADMIN"));
                System.out.println("Seeded admin user: admin / admin");
            }
        };
    }
}
