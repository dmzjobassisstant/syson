package org.eclipse.syson.auth.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.eclipse.syson.auth.entity.AuditEvent;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.repository.AuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {
    /**
     * Logs an audit event with the given parameters.
     *
     * @param actorId the actor ID as a string (will be parsed to UUID)
     * @param targetType the target type
     * @param targetId the target ID
     * @param type the audit event type
     * @param message the message to log
     */
    public void log(String actorId, String targetType, String targetId, AuditEventType type, String message) {
        UUID actor = null;
        try {
            actor = UUID.fromString(actorId);
        } catch (IllegalArgumentException e) {
            // leave as null
        }
        String action = type.name().toLowerCase().replace('_', '.');
        String metadata = jsonObject("message", message);
        this.record(action, actor, targetType, targetId, "success", metadata);
    }

    private final AuditEventRepository auditEventRepository;

    public AuditLogService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void recordAccountEvent(AuditEventType type, UUID actorId, UUID targetId, String metadata) {
        this.record(type.name().toLowerCase().replace('_', '.'), actorId, "account", targetId != null ? targetId.toString() : null,
                "success", jsonObject("message", metadata));
    }

    public void recordAccessDecision(UUID actorId, String targetType, String targetId, boolean allowed) {
        this.record("access.decision", actorId, targetType, targetId, allowed ? "allowed" : "denied", "{}");
    }

    public void recordRoleChange(UUID actorId, UUID targetId, String scope, String role) {
        this.record(AuditEventType.ADMIN_ROLE_ASSIGNED.name().toLowerCase().replace('_', '.'), actorId, scope,
                targetId != null ? targetId.toString() : null, "success", "{\"role\":\"" + role + "\"}");
    }

    public List<AuditEvent> findEvents(AuditEventSearchCriteria criteria) {
        int limit = criteria != null && criteria.limit() > 0 ? Math.min(criteria.limit(), 500) : 50;
        if (criteria != null && criteria.actorId() != null) {
            return this.auditEventRepository.findByActorIdOrderByCreatedAtDesc(criteria.actorId(), PageRequest.of(0, limit));
        }
        if (criteria != null && criteria.targetType() != null && criteria.targetId() != null) {
            return this.auditEventRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(criteria.targetType(), criteria.targetId(), PageRequest.of(0, limit));
        }
        return this.auditEventRepository.findAll(PageRequest.of(0, limit)).getContent();
    }

    public void record(String action, UUID actorId, String targetType, String targetId, String outcome, String metadata) {
        try {
            AuditEvent event = new AuditEvent();
            event.setTenantId(org.eclipse.syson.auth.TenantContext.getTenantIdAsUuid());
            event.setActorId(actorId != null ? actorId : org.eclipse.syson.auth.TenantContext.getUserIdAsUuid());
            event.setAction(action);
            event.setTargetType(targetType);
            event.setTargetId(targetId);
            event.setOutcome(outcome);
            event.setMetadata(metadata == null || metadata.isBlank() ? "{}" : metadata);
            event.setCreatedAt(OffsetDateTime.now());
            this.auditEventRepository.save(event);
        } catch (RuntimeException exception) {
            // Audit must never break the primary enterprise workflow.
        }
    }

    private static String jsonObject(String key, String value) {
        return "{\"" + escapeJson(key) + "\":\"" + escapeJson(value == null ? "" : value) + "\"}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
