package org.eclipse.syson.auth;

import java.util.UUID;

import org.eclipse.syson.auth.audit.RbacAuditTrailService;
import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Centralized admin audit trail logging service.
 * Wraps {@link RbacAuditTrailService} to provide a convenient method for
 * logging admin actions with actor context from {@link TenantContext}.
 *
 * @author syson-team
 */
@Service
public class AdminService {

    private final RbacAuditTrailService rbacAuditTrailService;
    private final UserRepository userRepository;

    public AdminService(RbacAuditTrailService rbacAuditTrailService, UserRepository userRepository) {
        this.rbacAuditTrailService = rbacAuditTrailService;
        this.userRepository = userRepository;
    }

    /**
     * Logs an admin audit event. Actor info is resolved from {@link TenantContext}.
     *
     * @param eventType   the event type (e.g. "user_created", "login_success")
     * @param targetType  the type of the target resource
     * @param targetId    the identifier of the target resource
     * @param targetEmail the email of the target user (if applicable)
     * @param projectId   the project context (if applicable)
     * @param oldJson     the previous value as JSON (if applicable)
     * @param newJson     the new value as JSON (if applicable)
     * @param reason      the reason for the change
     * @param request     the HTTP request (for IP address and user agent)
     */
    public void logEvent(String eventType, String targetType, String targetId,
                         String targetEmail, String projectId, String oldJson,
                         String newJson, String reason, HttpServletRequest request) {
        UUID actorId = TenantContext.getUserIdAsUuid();
        String actorEmail = null;
        String actorRole = null;

        if (actorId != null) {
            SysonUser actor = this.userRepository.findById(actorId).orElse(null);
            if (actor != null) {
                actorEmail = actor.getEmail();
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                actorRole = auth.getAuthorities().stream()
                        .map(Object::toString)
                        .findFirst().orElse(null);
            }
        }

        String ipAddress = request != null ? request.getRemoteAddr() : null;
        String userAgent = request != null ? request.getHeader("User-Agent") : null;

        this.rbacAuditTrailService.appendEvent(eventType, actorId, actorEmail, actorRole,
                targetType, targetId, targetEmail, projectId, oldJson, newJson, reason,
                ipAddress, userAgent);
    }

    /**
     * Logs an audit event with explicit actor info. Used for login events
     * where {@link TenantContext} may not be set.
     *
     * @param eventType   the event type
     * @param actorId     the actor's UUID (nullable for failed logins)
     * @param actorEmail  the actor's email
     * @param targetType  the type of the target resource
     * @param targetId    the identifier of the target resource
     * @param targetEmail the email of the target user
     * @param request     the HTTP request
     */
    public void logEventAs(String eventType, UUID actorId, String actorEmail,
                           String targetType, String targetId, String targetEmail,
                           HttpServletRequest request) {
        String actorRole = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            actorRole = auth.getAuthorities().stream()
                    .map(Object::toString)
                    .findFirst().orElse(null);
        }

        String ipAddress = request != null ? request.getRemoteAddr() : null;
        String userAgent = request != null ? request.getHeader("User-Agent") : null;

        this.rbacAuditTrailService.appendEvent(eventType, actorId, actorEmail, actorRole,
                targetType, targetId, targetEmail, null, null, null, null,
                ipAddress, userAgent);
    }
}
