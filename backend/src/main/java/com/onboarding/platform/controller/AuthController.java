package com.onboarding.platform.controller;

import com.onboarding.platform.dto.AuthResponse;
import com.onboarding.platform.dto.LoginRequest;
import com.onboarding.platform.dto.RegisterRequest;
import com.onboarding.platform.entity.User;
import com.onboarding.platform.enums.AuditAction;
import com.onboarding.platform.enums.Role;
import com.onboarding.platform.repository.UserRepository;
import com.onboarding.platform.security.AppUserDetailsService;
import com.onboarding.platform.security.JwtService;
import com.onboarding.platform.service.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AppUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final AuditService auditService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Email already registered");
        }

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .build();
        userRepository.save(user);
        auditService.recordAs(user.getEmail(), AuditAction.USER_REGISTERED, "USER", user.getId(), null);

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String token = jwtService.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, user.getEmail(), user.getRole().name()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (AuthenticationException e) {
            auditService.recordAs(request.email(), AuditAction.LOGIN_FAILED, "USER", null, null);
            throw e;
        }

        User user = userRepository.findByEmail(request.email()).orElseThrow();
        auditService.recordAs(user.getEmail(), AuditAction.LOGIN_SUCCEEDED, "USER", user.getId(), null);
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String token = jwtService.generateToken(userDetails);
        return ResponseEntity.ok(new AuthResponse(token, user.getEmail(), user.getRole().name()));
    }
}
