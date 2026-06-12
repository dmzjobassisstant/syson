package org.eclipse.syson.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.auth.entity.AuditEvent;
import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.model.ProjectRole;
import org.eclipse.syson.auth.model.TenantRole;
import org.eclipse.syson.auth.repository.UserRepository;
import org.eclipse.syson.auth.repository.ProjectMembershipRepository;
import org.eclipse.syson.auth.service.AccessControlService;
import org.eclipse.syson.auth.service.AccountAdministrationService;
import org.eclipse.syson.auth.service.AuditEventSearchCriteria;
import org.eclipse.syson.auth.service.AuditLogService;
import org.eclipse.syson.auth.service.CreateUserCommand;
import org.eclipse.syson.auth.service.PasswordResetService;
import org.eclipse.syson.auth.service.RoleManagementService;
import org.eclipse.syson.auth.service.UserSearchCriteria;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserRepository userRepository;
    private final ProjectMembershipRepository projectMembershipRepository;
    private final ProjectAccessService projectAccessService;
    private final PasswordEncoder passwordEncoder;
    private final AccountAdministrationService accountAdministrationService;
    private final PasswordResetService passwordResetService;
    private final RoleManagementService roleManagementService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final AdminService adminService;
    private final org.eclipse.syson.auth.audit.RbacAuditTrailService rbacAuditTrailService;
    private final org.eclipse.syson.history.service.ElementHistoryService elementHistoryService;
    private final EntityManager entityManager;

    public UserController(UserRepository userRepository,
                          ProjectMembershipRepository projectMembershipRepository,
                          ProjectAccessService projectAccessService,
                          PasswordEncoder passwordEncoder,
                          AccountAdministrationService accountAdministrationService,
                          PasswordResetService passwordResetService,
                          RoleManagementService roleManagementService,
                          AccessControlService accessControlService,
                          AuditLogService auditLogService,
                          AdminService adminService,
                          org.eclipse.syson.auth.audit.RbacAuditTrailService rbacAuditTrailService,
                          org.eclipse.syson.history.service.ElementHistoryService elementHistoryService,
                          EntityManager entityManager) {
        this.userRepository = userRepository;
        this.projectMembershipRepository = projectMembershipRepository;
        this.projectAccessService = projectAccessService;
        this.passwordEncoder = passwordEncoder;
        this.accountAdministrationService = accountAdministrationService;
        this.passwordResetService = passwordResetService;
        this.roleManagementService = roleManagementService;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.adminService = adminService;
        this.rbacAuditTrailService = rbacAuditTrailService;
        this.elementHistoryService = elementHistoryService;
        this.entityManager = entityManager;
    }

    private SysonUser currentUser() {
        UUID uid = TenantContext.getUserIdAsUuid();
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return this.userRepository.findById(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    @GetMapping("/me")
    public UserProfile me() {
        SysonUser u = this.currentUser();
        return new UserProfile(u.getId(), u.getEmail(), u.getName(), u.isActive(), u.isEmailVerified(), u.getLastLoginAt(), currentRoles());
    }

    private List<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replace("ROLE_", "").toLowerCase())
                .toList();
    }

    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ChangePasswordRequest req) {
        SysonUser user = this.currentUser();
        if (!this.passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        this.passwordResetService.adminResetPassword(user.getId(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password changed"));
    }

    @GetMapping("/me/projects")
    public List<MyProjectResponse> myProjects() {
        List<String> roles = currentRoles();
        boolean canSeeAllProjects = roles.stream()
                .anyMatch(role -> "superuser".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role));

        if (canSeeAllProjects) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = this.entityManager.createNativeQuery(
                    "SELECT id, name, created_on FROM project ORDER BY created_on DESC", Object[].class)
                    .getResultList();
            return rows.stream()
                    .map(row -> new MyProjectResponse(
                            row[0].toString(),
                            row[1] != null ? row[1].toString() : row[0].toString(),
                            "admin",
                            row[2] instanceof OffsetDateTime odt ? odt : null))
                    .toList();
        }

        // Pre-load all project names in one query for project-scoped users.
        @SuppressWarnings("unchecked")
        Map<String, String> projectNames = (Map<String, String>) this.entityManager.createNativeQuery(
                "SELECT id, name FROM project", Object[].class)
                .getResultList().stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> ((Object[]) row)[0].toString(),
                        row -> ((Object[]) row)[1] != null ? ((Object[]) row)[1].toString() : ((Object[]) row)[0].toString()));
        return this.projectAccessService.getMyProjects().stream()
                .map(m -> new MyProjectResponse(
                        m.getId().getProjectId(),
                        projectNames.getOrDefault(m.getId().getProjectId(), m.getId().getProjectId()),
                        m.getRole(),
                        m.getCreatedAt()))
                .toList();
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("user", "ok");
    }

    @PostMapping("/password/reset/request")
    public ResponseEntity<PasswordResetTokenResponse> requestPasswordReset(@RequestBody PasswordResetRequest req) {
        String token = this.passwordResetService.requestPasswordReset(req.email());
        return ResponseEntity.ok(new PasswordResetTokenResponse("Password reset requested", token));
    }

    @PostMapping("/password/reset/complete")
    public ResponseEntity<Map<String, String>> completePasswordReset(@RequestBody CompletePasswordResetRequest req) {
        this.passwordResetService.completePasswordReset(req.token(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password reset complete"));
    }

    @GetMapping("/admin/users")
    public List<UserProfile> adminListUsers(@RequestParam(required = false) String query, @RequestParam(required = false) Boolean active) {
        return this.accountAdministrationService.listUsers(new UserSearchCriteria(query, active)).stream()
                .map(user -> new UserProfile(user.getId(), user.getEmail(), user.getName(), user.isActive(), user.isEmailVerified(), user.getLastLoginAt(), List.of()))
                .toList();
    }

    @PostMapping("/admin/users")
    public ResponseEntity<?> adminCreateUser(@RequestBody CreateUserRequest req, HttpServletRequest request) {
        TenantRole role;
        try {
            role = TenantRole.from(req.tenantRole());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid tenantRole: '" + req.tenantRole() + "'. Valid values: viewer, editor, admin, superuser"));
        }
        SysonUser saved = this.accountAdministrationService.createUser(new CreateUserCommand(
                req.email(), req.name(), req.password(), req.tenantId(), role));
        this.adminService.logEvent("user_created", "user", saved.getId().toString(), saved.getEmail(), null, null, null, null, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new UserProfile(saved.getId(), saved.getEmail(), saved.getName(), saved.isActive(), saved.isEmailVerified(), saved.getLastLoginAt(), List.of()));
    }

    @PutMapping("/admin/users/{userId}/deactivate")
    public ResponseEntity<Map<String, String>> adminDeactivateUser(@PathVariable UUID userId, HttpServletRequest request) {
        SysonUser targetUser = this.userRepository.findById(userId).orElse(null);
        this.accountAdministrationService.deactivateUser(userId);
        this.adminService.logEvent("user_deactivated", "user", userId.toString(), targetUser != null ? targetUser.getEmail() : null, null, null, null, null, request);
        return ResponseEntity.ok(Map.of("message", "User deactivated"));
    }

    @PutMapping("/admin/users/{userId}/reactivate")
    public ResponseEntity<Map<String, String>> adminReactivateUser(@PathVariable UUID userId, HttpServletRequest request) {
        SysonUser targetUser = this.userRepository.findById(userId).orElse(null);
        this.accountAdministrationService.reactivateUser(userId);
        this.adminService.logEvent("user_reactivated", "user", userId.toString(), targetUser != null ? targetUser.getEmail() : null, null, null, null, null, request);
        return ResponseEntity.ok(Map.of("message", "User reactivated"));
    }

    @PutMapping("/admin/users/{userId}/password")
    public ResponseEntity<Map<String, String>> adminResetPassword(@PathVariable UUID userId, @RequestBody ResetPasswordRequest req, HttpServletRequest request) {
        SysonUser targetUser = this.userRepository.findById(userId).orElse(null);
        this.passwordResetService.adminResetPassword(userId, req.password());
        this.adminService.logEvent("password_reset", "user", userId.toString(), targetUser != null ? targetUser.getEmail() : null, null, null, null, null, request);
        return ResponseEntity.ok(Map.of("message", "Password reset"));
    }

    @PutMapping("/admin/tenants/{tenantId}/roles/{userId}")
    public ResponseEntity<Map<String, String>> adminAssignTenantRole(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @RequestBody AssignTenantRoleRequest req, HttpServletRequest request) {
        SysonUser targetUser = this.userRepository.findById(userId).orElse(null);
        this.roleManagementService.assignTenantRole(userId, tenantId, TenantRole.from(req.role()));
        this.adminService.logEvent("platform_role_changed", "user", userId.toString(), targetUser != null ? targetUser.getEmail() : null, null, null, "{\"role\":\"" + req.role() + "\"}", null, request);
        return ResponseEntity.ok(Map.of("message", "Tenant role assigned"));
    }

    @GetMapping("/admin/projects/{projectId}/members")
    public List<MemberResponse> adminProjectMembers(@PathVariable String projectId) {
        return this.projectAccessService.getProjectMembers(projectId).stream()
                .map(m -> {
                    SysonUser u = this.userRepository.findById(m.getId().getUserId()).orElse(null);
                    return new MemberResponse(m.getId().getUserId(), m.getId().getProjectId(),
                            u != null ? u.getEmail() : "unknown", u != null ? u.getName() : "Unknown", m.getRole());
                })
                .toList();
    }

    @PostMapping("/admin/projects/{projectId}/members")
    public ResponseEntity<Map<String, String>> adminGrantProjectRole(@PathVariable String projectId, @RequestBody AssignMemberRequest req, HttpServletRequest request) {
        boolean exists = this.projectMembershipRepository.existsByIdProjectIdAndIdUserId(projectId, req.userId());
        SysonUser targetUser = this.userRepository.findById(req.userId()).orElse(null);
        this.accessControlService.grantProjectRole(projectId, req.userId(), ProjectRole.from(req.role()));
        String eventType = exists ? "member_role_changed" : "member_added";
        this.adminService.logEvent(eventType, "project_member", req.userId().toString(), targetUser != null ? targetUser.getEmail() : null, projectId, null, "{\"role\":\"" + req.role() + "\"}", null, request);
        return ResponseEntity.ok(Map.of("message", "Project role granted"));
    }

    @DeleteMapping("/admin/projects/{projectId}/members/{userId}")
    public ResponseEntity<Map<String, String>> adminRevokeProjectRole(@PathVariable String projectId, @PathVariable UUID userId, HttpServletRequest request) {
        SysonUser targetUser = this.userRepository.findById(userId).orElse(null);
        this.accessControlService.revokeProjectRole(projectId, userId);
        this.adminService.logEvent("member_removed", "project_member", userId.toString(), targetUser != null ? targetUser.getEmail() : null, projectId, null, null, null, request);
        return ResponseEntity.ok(Map.of("message", "Project role revoked"));
    }

    @GetMapping("/admin/audit/events")
    public List<AuditEvent> adminAuditEvents(@RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(defaultValue = "100") int limit) {
        return this.auditLogService.findEvents(new AuditEventSearchCriteria(actorId, action, targetType, targetId, limit));
    }

    public record UserProfile(UUID id, String email, String name, boolean active, boolean emailVerified, OffsetDateTime lastLoginAt, List<String> roles) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
    public record MyProjectResponse(String projectId, String projectName, String role, OffsetDateTime assignedAt) {}
    public record CreateUserRequest(String email, String name, String password, UUID tenantId, String tenantRole) {}
    public record ResetPasswordRequest(String password) {}
    public record AssignTenantRoleRequest(String role) {}
    public record AssignMemberRequest(UUID userId, String role) {}
    public record MemberResponse(UUID userId, String projectId, String email, String name, String role) {}
    public record PasswordResetRequest(String email) {}
    public record CompletePasswordResetRequest(String token, String newPassword) {}
    public record PasswordResetTokenResponse(String message, String token) {}

    // ── RBAC Audit Trail (SuperUser only) ──────────────────────────────────

    @GetMapping("/admin/audit-trail")
    public ResponseEntity<?> getRbacAuditTrail(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            var pageable = org.springframework.data.domain.PageRequest.of(page, size,
                    org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "created_at"));
            var result = this.rbacAuditTrailService.queryEvents(eventType, targetType, targetId, projectId, actorId, null, null, pageable);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/admin/audit-trail/stats")
    public ResponseEntity<?> getRbacAuditTrailStats() {
        try {
            return ResponseEntity.ok(this.rbacAuditTrailService.getStats());
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Element history: returns all changes for a specific element.
     * Accessible to any authenticated user who is a member of the project.
     */
    @GetMapping("/projects/{projectId}/elements/{stableId}/history")
    public ResponseEntity<?> getElementHistory(
            @PathVariable String projectId,
            @PathVariable String stableId,
            @RequestParam(required = false) UUID branchId) {
        try {
            // Default to a null branch (service will query all branches)
            UUID branch = branchId != null ? branchId : UUID.fromString("00000000-0000-0000-0000-000000000000");
            var history = this.elementHistoryService.getElementHistory(projectId, stableId, branch);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("projectId", projectId);
            response.put("stableId", stableId);
            response.put("history", history);
            response.put("totalVersions", history.size());
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }
}
