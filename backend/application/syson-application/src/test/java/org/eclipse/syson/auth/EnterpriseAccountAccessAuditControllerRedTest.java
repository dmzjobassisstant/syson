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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Controller contract tests for enterprise account/access/admin endpoints.
 */
public class EnterpriseAccountAccessAuditControllerRedTest {

    @DisplayName("UserController exposes enterprise admin and self-service endpoints on existing mapped controller")
    @Test
    public void userControllerEnterpriseEndpointContract() {
        Class<?> controller = this.requiredClass("org.eclipse.syson.auth.UserController");

        this.assertHasMapping(controller, "adminListUsers", GetMapping.class, "/admin/users");
        this.assertHasMapping(controller, "adminCreateUser", PostMapping.class, "/admin/users");
        this.assertHasMapping(controller, "adminDeactivateUser", PutMapping.class, "/admin/users/{userId}/deactivate");
        this.assertHasMapping(controller, "adminReactivateUser", PutMapping.class, "/admin/users/{userId}/reactivate");
        this.assertHasMapping(controller, "adminResetPassword", PutMapping.class, "/admin/users/{userId}/password");
        this.assertHasMapping(controller, "adminAssignTenantRole", PutMapping.class, "/admin/tenants/{tenantId}/roles/{userId}");
        this.assertHasMapping(controller, "adminGrantProjectRole", PostMapping.class, "/admin/projects/{projectId}/members");
        this.assertHasMapping(controller, "adminRevokeProjectRole", DeleteMapping.class, "/admin/projects/{projectId}/members/{userId}");
        this.assertHasMapping(controller, "adminAuditEvents", GetMapping.class, "/admin/audit/events");
        this.assertHasMapping(controller, "requestPasswordReset", PostMapping.class, "/password/reset/request");
        this.assertHasMapping(controller, "completePasswordReset", PostMapping.class, "/password/reset/complete");
    }

    private Class<?> requiredClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            return fail("Missing controller class: " + className);
        }
    }

    private void assertHasMapping(Class<?> type, String methodName, Class<?> annotation, String expectedPath) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseGet(() -> fail("Missing method " + methodName));
        Set<String> paths = Set.of();
        if (annotation == GetMapping.class && method.isAnnotationPresent(GetMapping.class)) {
            paths = Set.of(method.getAnnotation(GetMapping.class).value());
        } else if (annotation == PostMapping.class && method.isAnnotationPresent(PostMapping.class)) {
            paths = Set.of(method.getAnnotation(PostMapping.class).value());
        } else if (annotation == PutMapping.class && method.isAnnotationPresent(PutMapping.class)) {
            paths = Set.of(method.getAnnotation(PutMapping.class).value());
        } else if (annotation == DeleteMapping.class && method.isAnnotationPresent(DeleteMapping.class)) {
            paths = Set.of(method.getAnnotation(DeleteMapping.class).value());
        } else {
            fail("Method " + methodName + " missing " + annotation.getSimpleName());
        }
        assertThat(paths).contains(expectedPath);
    }
}
