package org.eclipse.syson.locks.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "syson_merge_conflicts")
public class MergeConflict {

    @Id
    @Column(name = "conflict_id")
    private UUID conflictId;

    @Column(name = "merge_request_id")
    private UUID mergeRequestId;

    @Column(name = "object_type")
    private String objectType;

    @Column(name = "object_id")
    private String objectId;

    @Column(name = "field_path")
    private String fieldPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "base_value", columnDefinition = "jsonb")
    private String baseValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_value", columnDefinition = "jsonb")
    private String sourceValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_value", columnDefinition = "jsonb")
    private String targetValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolution", columnDefinition = "jsonb")
    private String resolution;

    @Column(name = "status")
    private String status;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    public MergeConflict() {
    }

    public UUID getConflictId() {
        return conflictId;
    }

    public void setConflictId(UUID conflictId) {
        this.conflictId = conflictId;
    }

    public UUID getMergeRequestId() {
        return mergeRequestId;
    }

    public void setMergeRequestId(UUID mergeRequestId) {
        this.mergeRequestId = mergeRequestId;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getFieldPath() {
        return fieldPath;
    }

    public void setFieldPath(String fieldPath) {
        this.fieldPath = fieldPath;
    }

    public String getBaseValue() {
        return baseValue;
    }

    public void setBaseValue(String baseValue) {
        this.baseValue = baseValue;
    }

    public String getSourceValue() {
        return sourceValue;
    }

    public void setSourceValue(String sourceValue) {
        this.sourceValue = sourceValue;
    }

    public String getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(String targetValue) {
        this.targetValue = targetValue;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(UUID resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
