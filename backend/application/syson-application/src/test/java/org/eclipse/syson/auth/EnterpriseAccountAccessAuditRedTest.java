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

/**
 * RED contract tests for enterprise account, access-control and audit services.
 *
 * <p>
 * These tests intentionally use reflection so that they compile quickly without a
 * full Sirius Web boot. They should stay red until the enterprise auth services
 * are implemented with the expected API surface.
 * </p>
 *
 * @author syson-team
 */
public class EnterpriseAccountAccessAuditRedTest {

    private static final String UUID = "UUID";

    private static final String STRING = "String";

    @DisplayName("Account administration service exposes user lifecycle operations")
    @Test
    public void accountAdministrationServiceContract() {
        Class<?> service = this.requiredClass("org.eclipse.syson.auth.service.AccountAdministrationService");

        this.assertHasMethod(service, "createUser", "CreateUserCommand");
        this.assertHasMethod(service, "deactivateUser", UUID);
        this.assertHasMethod(service, "reactivateUser", UUID);
        this.assertHasMethod(service, "listUsers", "UserSearchCriteria");
    }

    @DisplayName("Role management service supports tenant role changes with admin guardrails")
    @Test
    public void roleManagementServiceContract() {
        Class<?> service = this.requiredClass("org.eclipse.syson.auth.service.RoleManagementService");

        this.assertHasMethod(service, "assignTenantRole", UUID, UUID, "TenantRole");
        this.assertHasMethod(service, "removeTenantRole", UUID, UUID, "TenantRole");
        this.assertHasMethod(service, "requireTenantAdmin", UUID);
        this.requiredClass("org.eclipse.syson.auth.model.TenantRole");
    }

    @DisplayName("Password reset service supports token issue, consume and admin reset")
    @Test
    public void passwordResetServiceContract() {
        Class<?> service = this.requiredClass("org.eclipse.syson.auth.service.PasswordResetService");

        this.assertHasMethod(service, "requestPasswordReset", STRING);
        this.assertHasMethod(service, "completePasswordReset", STRING, STRING);
        this.assertHasMethod(service, "adminResetPassword", UUID, STRING);
        this.requiredClass("org.eclipse.syson.auth.entity.PasswordResetToken");
    }

    @DisplayName("Access-control service gates project and element read/write operations")
    @Test
    public void accessControlServiceContract() {
        Class<?> service = this.requiredClass("org.eclipse.syson.auth.service.AccessControlService");

        this.assertHasMethod(service, "canReadProject", UUID, STRING);
        this.assertHasMethod(service, "canWriteProject", UUID, STRING);
        this.assertHasMethod(service, "canReadElement", UUID, STRING, STRING);
        this.assertHasMethod(service, "canWriteElement", UUID, STRING, STRING);
        this.assertHasMethod(service, "grantProjectRole", STRING, UUID, "ProjectRole");
        this.assertHasMethod(service, "revokeProjectRole", STRING, UUID);
        this.requiredClass("org.eclipse.syson.auth.model.ProjectRole");
        this.requiredClass("org.eclipse.syson.auth.entity.ElementPermission");
    }

    @DisplayName("Audit log service records immutable security and access events")
    @Test
    public void auditLogServiceContract() {
        Class<?> service = this.requiredClass("org.eclipse.syson.auth.service.AuditLogService");

        this.assertHasMethod(service, "recordAccountEvent", "AuditEventType", UUID, UUID, STRING);
        this.assertHasMethod(service, "recordAccessDecision", UUID, STRING, STRING, "boolean");
        this.assertHasMethod(service, "recordRoleChange", UUID, UUID, STRING, STRING);
        this.assertHasMethod(service, "findEvents", "AuditEventSearchCriteria");
        this.requiredClass("org.eclipse.syson.auth.entity.AuditEvent");
        this.requiredClass("org.eclipse.syson.auth.model.AuditEventType");
        this.requiredClass("org.eclipse.syson.auth.repository.AuditEventRepository");
    }

    private Class<?> requiredClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            return fail("Missing enterprise auth contract class: " + className);
        }
    }

    private void assertHasMethod(Class<?> type, String methodName, String... parameterSimpleNames) {
        Set<String> candidates = Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(this::signature)
                .collect(java.util.stream.Collectors.toSet());

        String expected = methodName + "(" + String.join(",", parameterSimpleNames) + ")";
        assertThat(candidates)
                .as("Expected %s to expose %s; available overloads: %s", type.getName(), expected, candidates)
                .contains(expected);
    }

    private String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.joining(","));
        return method.getName() + "(" + parameters + ")";
    }
}
