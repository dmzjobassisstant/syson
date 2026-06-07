package org.eclipse.syson.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByTokenHashAndUsedAtIsNull(String tokenHash);
}
