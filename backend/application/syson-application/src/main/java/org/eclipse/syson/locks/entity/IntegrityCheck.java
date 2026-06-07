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
@Table(name = "syson_integrity_checks")
public class IntegrityCheck {

    @Id
    @Column(name = "check_id")
    private UUID checkId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "commit_id")
    private UUID commitId;

    @Column(name = "status")
    private String status;

    @Column(name = "error_count")
    private int errorCount;

    @Column(name = "warning_count")
    private int warningCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", columnDefinition = "jsonb")
    private String findings;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

    @Column(name = "checked_by")
    private UUID checkedBy;

    public IntegrityCheck() {
    }

    public UUID getCheckId() {
        return checkId;
    }

    public void setCheckId(UUID checkId) {
        this.checkId = checkId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public UUID getCommitId() {
        return commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public int getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(int warningCount) {
        this.warningCount = warningCount;
    }

    public String getFindings() {
        return findings;
    }

    public void setFindings(String findings) {
        this.findings = findings;
    }

    public OffsetDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(OffsetDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public UUID getCheckedBy() {
        return checkedBy;
    }

    public void setCheckedBy(UUID checkedBy) {
        this.checkedBy = checkedBy;
    }
}
