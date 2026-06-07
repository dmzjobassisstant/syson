package org.eclipse.syson.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "syson_audit_events")
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Column(name = "outcome")
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public UUID getId() { return this.id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return this.tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getActorId() { return this.actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getAction() { return this.action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return this.targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return this.targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getOutcome() { return this.outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getMetadata() { return this.metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public OffsetDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
