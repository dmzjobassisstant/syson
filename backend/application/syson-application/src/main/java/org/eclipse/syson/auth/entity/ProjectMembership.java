package org.eclipse.syson.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_project_members")
public class ProjectMembership {

    @EmbeddedId
    private ProjectMembershipId id;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public ProjectMembership() {
    }

    public ProjectMembership(String projectId, UUID userId, String role) {
        this.id = new ProjectMembershipId(projectId, userId);
        this.role = role;
        this.createdAt = OffsetDateTime.now();
    }

    public ProjectMembershipId getId() {
        return this.id;
    }

    public void setId(ProjectMembershipId id) {
        this.id = id;
    }

    public String getRole() {
        return this.role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Embeddable
    public static class ProjectMembershipId implements Serializable {
        @Column(name = "project_id", nullable = false)
        private String projectId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        public ProjectMembershipId() {
        }

        public ProjectMembershipId(String projectId, UUID userId) {
            this.projectId = projectId;
            this.userId = userId;
        }

        public String getProjectId() {
            return this.projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public UUID getUserId() {
            return this.userId;
        }

        public void setUserId(UUID userId) {
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProjectMembershipId that)) return false;
            return Objects.equals(this.projectId, that.projectId)
                && Objects.equals(this.userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.projectId, this.userId);
        }
    }
}
