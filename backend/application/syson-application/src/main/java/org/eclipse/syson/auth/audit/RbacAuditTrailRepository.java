package org.eclipse.syson.auth.audit;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RbacAuditTrailRepository extends JpaRepository<RbacAuditTrailEntity, UUID> {

    List<RbacAuditTrailEntity> findByEventTypeOrderByCreatedAtDesc(String eventType, Pageable pageable);

    List<RbacAuditTrailEntity> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId, Pageable pageable);

    List<RbacAuditTrailEntity> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    List<RbacAuditTrailEntity> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);

    List<RbacAuditTrailEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(Timestamp from, Timestamp to, Pageable pageable);

    @Query(value = "SELECT * FROM syson_rbac_audit_trail e WHERE "
            + "(:eventType IS NULL OR e.event_type = :eventType) AND "
            + "(:targetType IS NULL OR e.target_type = :targetType) AND "
            + "(:targetId IS NULL OR e.target_id = :targetId) AND "
            + "(:projectId IS NULL OR e.project_id = :projectId) "
            + "ORDER BY e.created_at DESC",
            countQuery = "SELECT count(*) FROM syson_rbac_audit_trail e WHERE "
            + "(:eventType IS NULL OR e.event_type = :eventType) AND "
            + "(:targetType IS NULL OR e.target_type = :targetType) AND "
            + "(:targetId IS NULL OR e.target_id = :targetId) AND "
            + "(:projectId IS NULL OR e.project_id = :projectId)",
            nativeQuery = true)
    Page<RbacAuditTrailEntity> searchEvents(
            @Param("eventType") String eventType,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("projectId") String projectId,
            Pageable pageable);

    @Query("SELECT e.eventType AS eventType, COUNT(e) AS cnt FROM RbacAuditTrailEntity e GROUP BY e.eventType")
    List<Object[]> countByEventType();
}
