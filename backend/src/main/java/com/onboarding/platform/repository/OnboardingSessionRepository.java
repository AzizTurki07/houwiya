package com.onboarding.platform.repository;

import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OnboardingSessionRepository extends JpaRepository<OnboardingSession, UUID> {
    List<OnboardingSession> findByUser(User user);
}
