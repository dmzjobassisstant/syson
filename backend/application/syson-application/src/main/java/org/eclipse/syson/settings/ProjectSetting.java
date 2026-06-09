package org.eclipse.syson.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_project_settings")
public class ProjectSetting {

    @EmbeddedId
    private ProjectSettingId id;

    @Column(name = "value", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String value;

    @Column(name = "description")
    private String description;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public ProjectSetting() {
    }

    public ProjectSetting(String projectId, String key, String value, String description) {
        this.id = new ProjectSettingId(projectId, key);
        this.value = value;
        this.description = description;
        this.updatedAt = OffsetDateTime.now();
    }

    public ProjectSettingId getId() { return id; }
    public void setId(ProjectSettingId id) { this.id = id; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Embeddable
    public static class ProjectSettingId implements Serializable {
        @Column(name = "project_id")
        private String projectId;

        @Column(name = "key")
        private String key;

        public ProjectSettingId() {}

        public ProjectSettingId(String projectId, String key) {
            this.projectId = projectId;
            this.key = key;
        }

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProjectSettingId that = (ProjectSettingId) o;
            return Objects.equals(projectId, that.projectId) && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, key);
        }
    }
}
