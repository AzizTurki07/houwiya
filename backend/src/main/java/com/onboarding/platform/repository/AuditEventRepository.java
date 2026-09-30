package com.onboarding.platform.repository;

import com.onboarding.platform.entity.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);

    List<AuditEvent> findByTargetIdOrderByOccurredAtAsc(UUID targetId);
}
