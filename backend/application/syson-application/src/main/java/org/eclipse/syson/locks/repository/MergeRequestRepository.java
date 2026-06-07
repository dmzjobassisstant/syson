package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.MergeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MergeRequestRepository extends JpaRepository<MergeRequest, UUID> {

    List<MergeRequest> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

    Optional<MergeRequest> findByMergeRequestId(UUID mergeRequestId);
}
