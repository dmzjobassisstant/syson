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
package org.eclipse.syson.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.eclipse.syson.auth.entity.SysonTenant;
import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.entity.TenantMembership;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.eclipse.syson.auth.repository.TenantRepository;
import org.eclipse.syson.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Seeds the bootstrap superuser and default tenant on application startup.
 * <p>
 * Reads credentials from environment variables:
 * <ul>
 *   <li>{@code SYSON_BOOTSTRAP_EMAIL} (default: {@code admin})</li>
 *   <li>{@code SYSON_BOOTSTRAP_PASSWORD} (default: {@code admin})</li>
 * </ul>
 * If the user already exists, seeding is skipped.
 * </p>
 *
 * @author syson-team
 */
@Component
public class AdminSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSeeder.class);

    private static final String DEFAULT_EMAIL = "admin";

    /**
     * No hardcoded default password — must be set via environment variable.
     * The previous default ('admin') was a security risk for internet-facing deployments.
     */
    private static final String NO_DEFAULT_CREDENTIAL_MESSAGE = 
        "SYSON_BOOTSTRAP_PASSWORD or SYSON_AUTH_ADMIN_PASSWORD must be set. "
        + "Default 'admin' password is no longer supported for security reasons.";

    private static final String DEFAULT_NAME = "SuperUser";

    private static final String DEFAULT_TENANT_NAME = "Default Organization";

    private static final UUID DEFAULT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserRepository userRepository;

    private final TenantRepository tenantRepository;

    private final MembershipRepository membershipRepository;

    private final PasswordEncoder passwordEncoder;

    public AdminSeeder(UserRepository userRepository,
                       TenantRepository tenantRepository,
                       MembershipRepository membershipRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void seed() {
        String bootstrapEmail = this.getEnv("SYSON_BOOTSTRAP_EMAIL", this.getEnv("SYSON_AUTH_ADMIN_EMAIL", DEFAULT_EMAIL));
        String bootstrapPassword = this.getEnv("SYSON_BOOTSTRAP_PASSWORD", this.getEnv("SYSON_AUTH_ADMIN_PASSWORD", null));

        if (bootstrapPassword == null || bootstrapPassword.isBlank()) {
            // Generate a strong random password — prevents internet-facing deployments from defaulting to 'admin'
            bootstrapPassword = this.generateRandomPassword();
            LOG.warn("==============================================================");
            LOG.warn("  NO BOOTSTRAP PASSWORD SET — generated random credential.");
            LOG.warn("  Set SYSON_BOOTSTRAP_PASSWORD env var to persist this across restarts.");
            LOG.warn("  Email:    {}", bootstrapEmail);
            LOG.warn("  Password: {}", bootstrapPassword);
            LOG.warn("  SAVE THIS PASSWORD. It will NOT be shown again.");
            LOG.warn("==============================================================");
        }

        // Ensure default tenant exists
        if (this.tenantRepository.findById(DEFAULT_TENANT_ID).isEmpty()) {
            SysonTenant tenant = new SysonTenant();
            tenant.setId(DEFAULT_TENANT_ID);
            tenant.setName(DEFAULT_TENANT_NAME);
            tenant.setMode("onprem");
            tenant.setCreatedAt(OffsetDateTime.now());
            tenant.setUpdatedAt(OffsetDateTime.now());
            this.tenantRepository.save(tenant);
            LOG.info("Seeded default tenant: {} (id={})", DEFAULT_TENANT_NAME, DEFAULT_TENANT_ID);
        }

        // Skip if superuser already exists
        if (this.userRepository.existsByEmail(bootstrapEmail)) {
            LOG.info("Bootstrap superuser '{}' already exists. Skipping seed.", bootstrapEmail);
            return;
        }

        SysonUser user = new SysonUser();
        user.setId(DEFAULT_USER_ID);
        user.setEmail(bootstrapEmail);
        user.setName(DEFAULT_NAME);
        user.setPasswordHash(this.passwordEncoder.encode(bootstrapPassword));
        user.setActive(true);
        user.setFailedLoginAttempts(0);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        this.userRepository.save(user);

        TenantMembership membership = new TenantMembership(DEFAULT_USER_ID, DEFAULT_TENANT_ID, "superuser");
        membership.setCreatedAt(OffsetDateTime.now());
        this.membershipRepository.save(membership);

        LOG.info("Seeded bootstrap superuser: {} (id={}) with role 'superuser'", bootstrapEmail, DEFAULT_USER_ID);
    }

    private String generateRandomPassword() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+";
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
