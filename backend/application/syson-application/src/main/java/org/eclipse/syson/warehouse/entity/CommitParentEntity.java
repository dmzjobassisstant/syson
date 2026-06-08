package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_commit_parents")
public class CommitParentEntity {
    @EmbeddedId private CommitParentId id;
    @Column(name = "parent_order") private int parentOrder;

    public CommitParentEntity() {}
    public CommitParentEntity(UUID commitId, UUID parentCommitId, int order) {
        this.id = new CommitParentId(commitId, parentCommitId);
        this.parentOrder = order;
    }
    public CommitParentId getId() { return id; }
    public void setId(CommitParentId id) { this.id = id; }
    public int getParentOrder() { return parentOrder; }
    public void setParentOrder(int v) { this.parentOrder = v; }

    @Embeddable
    public static class CommitParentId implements Serializable {
        @Column(name = "commit_id") private UUID commitId;
        @Column(name = "parent_commit_id") private UUID parentCommitId;
        public CommitParentId() {}
        public CommitParentId(UUID c, UUID p) { this.commitId = c; this.parentCommitId = p; }
        public UUID getCommitId() { return commitId; }
        public void setCommitId(UUID v) { this.commitId = v; }
        public UUID getParentCommitId() { return parentCommitId; }
        public void setParentCommitId(UUID v) { this.parentCommitId = v; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CommitParentId c)) return false;
            return Objects.equals(commitId, c.commitId) && Objects.equals(parentCommitId, c.parentCommitId);
        }
        @Override public int hashCode() { return Objects.hash(commitId, parentCommitId); }
    }
}
