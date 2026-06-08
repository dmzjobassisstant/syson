package org.eclipse.syson.auth.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class RbacAuditTrailService {

    private final RbacAuditTrailRepository rbacAuditTrailRepository;

    public RbacAuditTrailService(RbacAuditTrailRepository rbacAuditTrailRepository) {
        this.rbacAuditTrailRepository = rbacAuditTrailRepository;
    }

    /**
     * Appends a new RBAC audit trail event. This method only performs INSERT operations
     * and never updates or deletes existing records.
     *
     * @param eventType   the type of the event
     * @param actorId     the UUID of the actor performing the action
     * @param actorEmail  the email of the actor
     * @param actorRole   the role of the actor at the time of the event
     * @param targetType  the type of the target resource
     * @param targetId    the identifier of the target resource
     * @param targetEmail the email of the target user (if applicable)
     * @param projectId   the project context (if applicable)
     * @param oldValueJson the previous value as a JSON string (if applicable)
     * @param newValueJson the new value as a JSON string (if applicable)
     * @param reason      the reason for the change
     * @param ipAddress   the IP address of the actor
     * @param userAgent   the user agent of the actor
     */
    public void appendEvent(String eventType, UUID actorId, String actorEmail, String actorRole,
                            String targetType, String targetId, String targetEmail, String projectId,
                            String oldValueJson, String newValueJson, String reason,
                            String ipAddress, String userAgent) {
        try {
            RbacAuditTrailEntity entity = new RbacAuditTrailEntity();
            entity.setEventType(eventType);
            entity.setActorId(actorId);
            entity.setActorEmail(actorEmail);
            entity.setActorRole(actorRole);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setTargetEmail(targetEmail);
            entity.setProjectId(projectId);
            entity.setOldValue(oldValueJson);
            entity.setNewValue(newValueJson);
            entity.setReason(reason);
            entity.setIpAddress(ipAddress);
            entity.setUserAgent(userAgent);
            entity.setCreatedAt(Timestamp.from(Instant.now()));
            this.rbacAuditTrailRepository.save(entity);
        } catch (RuntimeException exception) {
            // Audit must never break the primary workflow.
        }
    }

    /**
     * Queries RBAC audit trail events with optional filters. All filter parameters
     * are optional; null values are ignored.
     *
     * @param eventType  filter by event type (nullable)
     * @param targetType filter by target type (nullable)
     * @param targetId   filter by target id (nullable)
     * @param projectId  filter by project id (nullable)
     * @param actorId    filter by actor id (nullable)
     * @param from       start of the time range (nullable)
     * @param to         end of the time range (nullable)
     * @param pageable   pagination and sorting
     * @return a page of matching audit trail entities
     */
    public Page<RbacAuditTrailEntity> queryEvents(String eventType, String targetType, String targetId,
                                                   String projectId, UUID actorId, Timestamp from, Timestamp to,
                                                   Pageable pageable) {
        return this.rbacAuditTrailRepository.searchEvents(eventType, targetType, targetId, projectId, pageable);
    }

    /**
     * Returns a count of audit trail events grouped by event type.
     *
     * @return a map of event type to count
     */
    public Map<String, Long> getStats() {
        List<Object[]> rows = this.rbacAuditTrailRepository.countByEventType();
        Map<String, Long> stats = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String eventType = (String) row[0];
            Long count = (Long) row[1];
            stats.put(eventType, count);
        }
        return stats;
    }
}
