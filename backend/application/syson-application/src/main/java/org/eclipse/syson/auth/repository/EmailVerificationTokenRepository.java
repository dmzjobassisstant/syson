package org.eclipse.syson.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByTokenHashAndUsedAtIsNull(String tokenHash);
}
