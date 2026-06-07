package org.eclipse.syson.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByTokenHashAndAcceptedAtIsNullAndRevokedAtIsNull(String tokenHash);
    List<Invitation> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<Invitation> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
