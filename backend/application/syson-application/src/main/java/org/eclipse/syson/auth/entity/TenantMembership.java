/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code syson_tenant_memberships} table.
 * Uses an {@link EmbeddedId} with a composite key of {@code user_id} and {@code tenant_id}.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_tenant_memberships")
public class TenantMembership {

    @EmbeddedId
    private TenantMembershipId id;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public TenantMembership() {
    }

    public TenantMembership(UUID userId, UUID tenantId, String role) {
        this.id = new TenantMembershipId(userId, tenantId);
        this.role = role;
    }

    public TenantMembershipId getId() {
        return this.id;
    }

    public void setId(TenantMembershipId id) {
        this.id = id;
    }

    public UUID getUserId() {
        return this.id != null ? this.id.getUserId() : null;
    }

    public UUID getTenantId() {
        return this.id != null ? this.id.getTenantId() : null;
    }

    public String getRole() {
        return this.role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Embeddable composite primary key for {@link TenantMembership}.
     */
    @Embeddable
    public static class TenantMembershipId implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "user_id")
        private UUID userId;

        @Column(name = "tenant_id")
        private UUID tenantId;

        public TenantMembershipId() {
        }

        public TenantMembershipId(UUID userId, UUID tenantId) {
            this.userId = userId;
            this.tenantId = tenantId;
        }

        public UUID getUserId() {
            return this.userId;
        }

        public void setUserId(UUID userId) {
            this.userId = userId;
        }

        public UUID getTenantId() {
            return this.tenantId;
        }

        public void setTenantId(UUID tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TenantMembershipId that)) {
                return false;
            }
            return Objects.equals(this.userId, that.userId) && Objects.equals(this.tenantId, that.tenantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.userId, this.tenantId);
        }
    }
}
