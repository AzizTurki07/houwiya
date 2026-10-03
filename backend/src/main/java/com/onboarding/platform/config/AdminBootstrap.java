package com.onboarding.platform.config;

import com.onboarding.platform.entity.User;
import com.onboarding.platform.enums.AuditAction;
import com.onboarding.platform.enums.Role;
import com.onboarding.platform.repository.UserRepository;
import com.onboarding.platform.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Registration only ever creates USER accounts, so the first admin comes from configuration:
 * ADMIN_EMAIL + ADMIN_PASSWORD create that account (or promote an existing one) at startup.
 * The password is only used when the account is created -- it never overwrites a changed one.
 *
 * Several instances can start at once (e.g. Kubernetes replicas): if another one creates the
 * account first, the unique e-mail constraint rejects ours, and that's fine -- it exists.
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder, AuditService auditService,
                          @Value("${app.admin.email:}") String email,
                          @Value("${app.admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty()) {
            return;
        }
        userRepository.findByEmail(email).ifPresentOrElse(existing -> {
            if (existing.getRole() != Role.ADMIN) {
                existing.setRole(Role.ADMIN);
                userRepository.save(existing);
                log.info("Promoted {} to ADMIN", email);
                auditService.recordAs("system", AuditAction.ADMIN_ACCOUNT_BOOTSTRAPPED, "USER", existing.getId(), "promoted");
            }
        }, () -> {
            if (password.length() < 12) {
                throw new IllegalStateException("ADMIN_PASSWORD must be at least 12 characters to create the admin account");
            }
            User admin;
            try {
                admin = userRepository.save(User.builder()
                        .email(email)
                        .passwordHash(passwordEncoder.encode(password))
                        .role(Role.ADMIN)
                        .build());
            } catch (DataIntegrityViolationException createdConcurrently) {
                log.info("Admin account {} was created by another instance", email);
                return;
            }
            log.info("Created admin account {}", email);
            auditService.recordAs("system", AuditAction.ADMIN_ACCOUNT_BOOTSTRAPPED, "USER", admin.getId(), "created");
        });
    }
}
