package com.example.vehiclebackend.service;

import com.example.vehiclebackend.security.JwtUtil;
import com.example.vehiclebackend.security.LoginAttemptService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptService loginAttempts;

    public AuthService(UserDetailsService userDetailsService,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       LoginAttemptService loginAttempts) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.loginAttempts = loginAttempts;
    }

    public record LoginResult(String token, String role) {}

    /**
     * @throws BadCredentialsException on a wrong username or password
     * @throws com.example.vehiclebackend.security.LoginLockedException when this
     *         username is locked out after repeated failures — checked before the
     *         account lookup, so unknown usernames behave identically
     */
    public LoginResult login(String username, String password) {
        loginAttempts.assertNotLocked(username);
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            loginAttempts.recordFailure(username);
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            loginAttempts.recordFailure(username);
            throw new BadCredentialsException("Invalid credentials");
        }
        loginAttempts.recordSuccess(username);
        String role = user.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority())
                .orElse("ROLE_USER");
        return new LoginResult(jwtUtil.generateToken(username), role);
    }
}
