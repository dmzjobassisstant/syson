package org.eclipse.syson.auth.repository;

import java.util.List;
import java.util.UUID;

import org.eclipse.syson.auth.entity.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
    List<AuditEvent> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);
    List<AuditEvent> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId, Pageable pageable);
}
