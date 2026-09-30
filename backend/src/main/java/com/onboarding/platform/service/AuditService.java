package com.onboarding.platform.service;

import com.onboarding.platform.entity.AuditEvent;
import com.onboarding.platform.enums.AuditAction;
import com.onboarding.platform.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Writes the audit trail. Callers pass ids and short, non-personal details only: the log must
 * not become a second copy of the document data it is there to protect.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;

    /** Records an action by the currently signed-in user. */
    public void record(AuditAction action, String targetType, UUID targetId, String details) {
        recordAs(currentActor(), action, targetType, targetId, details);
    }

    public void recordAs(String actor, AuditAction action, String targetType, UUID targetId, String details) {
        repository.save(AuditEvent.builder()
                .actor(actor)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .details(details == null || details.length() <= 500 ? details : details.substring(0, 500))
                .build());
    }

    public List<AuditEvent> latest(int limit) {
        return repository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    public List<AuditEvent> forTarget(UUID targetId) {
        return repository.findByTargetIdOrderByOccurredAtAsc(targetId);
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken ? null : auth.getName();
    }
}
