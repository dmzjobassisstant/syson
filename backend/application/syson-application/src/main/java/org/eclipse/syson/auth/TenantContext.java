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

import java.util.UUID;

/**
 * Thread-local tenant context, resolved from JWT claims by {@link TenantFilter}.
 * Mirrors a server-side multi-tenant pattern: each request carries one tenant.
 *
 * @author syson-team
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    private TenantContext() {
        // utility class
    }

    /**
     * Sets the current tenant and user ID for the duration of this request.
     *
     * @param tenantId the tenant ID from the JWT claim
     * @param userId   the authenticated user's subject
     */
    public static void set(UUID tenantId, String userId) {
        CURRENT_TENANT.set(tenantId);
        CURRENT_USER_ID.set(userId);
    }

    /**
     * Returns the tenant ID for the current request, or {@code null} if not set.
     */
    public static UUID getTenantId() {
        return CURRENT_TENANT.get();
    }

    /**
     * Returns the user ID for the current request, or {@code null} if not set.
     */
    public static String getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * Returns the user ID as a UUID, or {@code null} if not set.
     */
    public static UUID getUserIdAsUuid() {
        String uid = CURRENT_USER_ID.get();
        return uid != null ? UUID.fromString(uid) : null;
    }

    /**
     * Clears the thread-local storage. Must be called at the end of each
     * request to prevent leaking context across requests.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_USER_ID.remove();
    }
}
