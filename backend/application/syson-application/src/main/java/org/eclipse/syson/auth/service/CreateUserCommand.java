package org.eclipse.syson.auth.service;

import java.util.UUID;

import org.eclipse.syson.auth.model.TenantRole;

public record CreateUserCommand(String email, String name, String password, UUID tenantId, TenantRole tenantRole) {
}
