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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A {@link OncePerRequestFilter} that runs after {@link JwtAuthenticationFilter}
 * and sets the {@link TenantContext} thread-local for the duration of the request.
 * <p>
 * Tenant and user information is read from request attributes that were set
 * by {@link JwtAuthenticationFilter} after successful token validation.
 * Public paths (that skip JWT authentication) are also skipped here.
 * </p>
 *
 * @author syson-team
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    /** Request attribute names set by JwtAuthenticationFilter. */
    static final String TENANT_ID_ATTR = "syson.tenant_id";
    static final String USER_ID_ATTR = "syson.user_id";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/graphql",
            "/actuator"
    );

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String path = request.getServletPath();

        // Skip on public paths (no JWT, so no tenant context)
        if (this.isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Read tenant and user from request attributes set by JwtAuthenticationFilter
        Object tenantIdAttr = request.getAttribute(TENANT_ID_ATTR);
        Object userIdAttr = request.getAttribute(USER_ID_ATTR);

        if (tenantIdAttr instanceof UUID tenantId && userIdAttr instanceof String userId) {
            TenantContext.set(tenantId, userId);
        }
        // If no attributes (e.g. missing/invalid token on protected path),
        // leave TenantContext unset; downstream code will see null.

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
}
