package org.eclipse.syson.auth.service;

import java.util.UUID;

public record AuditEventSearchCriteria(UUID actorId, String action, String targetType, String targetId, int limit) {
}
