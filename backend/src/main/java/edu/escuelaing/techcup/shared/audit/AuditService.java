package edu.escuelaing.techcup.shared.audit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central audit trail (an Observer-style collaborator: use cases notify it, it records).
 * {@link #record} joins the caller's transaction, so an audit row is written if and only if the
 * audited use case commits.
 */
@Service
public class AuditService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Long actorUserId, AuditAction action, String entityType, Long entityId,
                       Map<String, Object> details) {
        repository.save(AuditLog.builder()
                .actorUserId(actorUserId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(details == null || details.isEmpty() ? null : details)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Long actorUserId, AuditAction action, String entityType, Long entityId) {
        record(actorUserId, action, entityType, entityId, null);
    }

    /**
     * Records the event in its own transaction, so it survives the rollback of the use case that
     * raised it. Used for events whose whole point is that the use case failed, e.g. a rejected
     * login attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDetached(Long actorUserId, AuditAction action, String entityType, Long entityId,
                               Map<String, Object> details) {
        record(actorUserId, action, entityType, entityId, details);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> query(AuditAction action, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        PageRequest page = PageRequest.of(0, size);
        List<AuditLog> logs = action == null
                ? repository.findAllByOrderByCreatedAtDesc(page)
                : repository.findByActionOrderByCreatedAtDesc(action, page);
        Map<Long, String> actorNames = resolveActorNames(logs);
        return logs.stream()
                .map(log -> AuditLogResponse.from(log, actorNames.get(log.getActorUserId())))
                .toList();
    }

    private Map<Long, String> resolveActorNames(List<AuditLog> logs) {
        Set<Long> ids = logs.stream()
                .map(AuditLog::getActorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findActorNames(ids).stream()
                .collect(Collectors.toMap(AuditRepository.ActorName::getId, AuditRepository.ActorName::getFullName));
    }
}
