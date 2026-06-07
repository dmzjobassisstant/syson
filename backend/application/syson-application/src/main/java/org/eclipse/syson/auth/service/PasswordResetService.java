package org.eclipse.syson.auth.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.eclipse.syson.auth.entity.PasswordResetToken;
import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.repository.PasswordResetTokenRepository;
import org.eclipse.syson.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditLogService auditLogService;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder, TokenService tokenService, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public String requestPasswordReset(String email) {
        String token = this.tokenService.generateOpaqueToken();
        this.userRepository.findByEmail(email.trim().toLowerCase()).ifPresent(user -> {
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUserId(user.getId());
            resetToken.setTokenHash(this.tokenService.sha256(token));
            resetToken.setCreatedAt(OffsetDateTime.now());
            resetToken.setExpiresAt(OffsetDateTime.now().plusHours(2));
            this.tokenRepository.save(resetToken);
            this.auditLogService.recordAccountEvent(AuditEventType.AUTH_PASSWORD_CHANGED, user.getId(), user.getId(), "reset requested");
        });
        return token;
    }

    @Transactional
    public void completePasswordReset(String token, String newPassword) {
        PasswordResetToken resetToken = this.tokenRepository.findByTokenHashAndUsedAtIsNull(this.tokenService.sha256(token)).orElseThrow();
        if (resetToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Password reset token expired");
        }
        this.adminResetPassword(resetToken.getUserId(), newPassword);
        resetToken.setUsedAt(OffsetDateTime.now());
        this.tokenRepository.save(resetToken);
    }

    @Transactional
    public void adminResetPassword(UUID userId, String newPassword) {
        SysonUser user = this.userRepository.findById(userId).orElseThrow();
        user.setPasswordHash(this.passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        this.userRepository.save(user);
        this.auditLogService.recordAccountEvent(AuditEventType.ADMIN_PASSWORD_RESET, userId, userId, user.getEmail());
    }
}
