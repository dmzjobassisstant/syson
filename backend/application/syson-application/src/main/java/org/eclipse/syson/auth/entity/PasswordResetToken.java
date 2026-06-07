package org.eclipse.syson.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "syson_password_reset_tokens")
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "created_ip")
    private String createdIp;

    @Column(name = "user_agent")
    private String userAgent;

    public UUID getId() { return this.id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return this.userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTokenHash() { return this.tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public OffsetDateTime getExpiresAt() { return this.expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getUsedAt() { return this.usedAt; }
    public void setUsedAt(OffsetDateTime usedAt) { this.usedAt = usedAt; }
    public OffsetDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public String getCreatedIp() { return this.createdIp; }
    public void setCreatedIp(String createdIp) { this.createdIp = createdIp; }
    public String getUserAgent() { return this.userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
