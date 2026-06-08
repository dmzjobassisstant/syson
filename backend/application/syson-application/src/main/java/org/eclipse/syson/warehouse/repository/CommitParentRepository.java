package org.eclipse.syson.warehouse.repository;

import org.eclipse.syson.warehouse.entity.CommitParentEntity;
import org.eclipse.syson.warehouse.entity.CommitParentEntity.CommitParentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface CommitParentRepository extends JpaRepository<CommitParentEntity, CommitParentId> {
    List<CommitParentEntity> findByIdCommitId(UUID commitId);
}
