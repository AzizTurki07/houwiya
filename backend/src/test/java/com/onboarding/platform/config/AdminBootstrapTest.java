package com.onboarding.platform.config;

import com.onboarding.platform.entity.User;
import com.onboarding.platform.repository.UserRepository;
import com.onboarding.platform.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    @Test
    void anotherInstanceCreatingTheAdminFirstIsNotAStartupFailure() {
        // Two backend replicas starting together: both see no admin, ours loses the insert race.
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        when(users.findByEmail("admin@example.test")).thenReturn(Optional.empty());
        when(encoder.encode(any())).thenReturn("hash");
        when(users.save(any(User.class))).thenThrow(new DataIntegrityViolationException("app_user_email_key"));

        AdminBootstrap bootstrap = new AdminBootstrap(users, encoder, audit, "admin@example.test", "a-long-enough-password");

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verifyNoInteractions(audit);
    }
}
