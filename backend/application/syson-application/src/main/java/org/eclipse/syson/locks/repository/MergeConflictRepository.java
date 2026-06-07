package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.MergeConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MergeConflictRepository extends JpaRepository<MergeConflict, UUID> {

    List<MergeConflict> findByMergeRequestIdAndStatus(UUID mergeRequestId, String status);

    long countByMergeRequestIdAndStatus(UUID mergeRequestId, String status);
}
