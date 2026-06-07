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

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserRepository userRepository;
    private final ProjectAccessService projectAccessService;
    private final PasswordEncoder passwordEncoder;
    private final AccountAdministrationService accountAdministrationService;
    private final PasswordResetService passwordResetService;
    private final RoleManagementService roleManagementService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    public UserController(UserRepository userRepository,
                          ProjectAccessService projectAccessService,
                          PasswordEncoder passwordEncoder,
                          AccountAdministrationService accountAdministrationService,
                          PasswordResetService passwordResetService,
                          RoleManagementService roleManagementService,
                          AccessControlService accessControlService,
                          AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.projectAccessService = projectAccessService;
        this.passwordEncoder = passwordEncoder;
        this.accountAdministrationService = accountAdministrationService;
        this.passwordResetService = passwordResetService;
        this.roleManagementService = roleManagementService;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
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
        return new UserProfile(u.getId(), u.getEmail(), u.getName(), u.isActive(), u.isEmailVerified(), u.getLastLoginAt());
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
        return this.projectAccessService.getMyProjects().stream()
                .map(m -> new MyProjectResponse(m.getId().getProjectId(), m.getRole(), m.getCreatedAt()))
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
                .map(user -> new UserProfile(user.getId(), user.getEmail(), user.getName(), user.isActive(), user.isEmailVerified(), user.getLastLoginAt()))
                .toList();
    }

    @PostMapping("/admin/users")
    public ResponseEntity<UserProfile> adminCreateUser(@RequestBody CreateUserRequest req) {
        SysonUser saved = this.accountAdministrationService.createUser(new CreateUserCommand(
                req.email(), req.name(), req.password(), req.tenantId(), TenantRole.from(req.tenantRole())));
        return ResponseEntity.status(HttpStatus.CREATED).body(new UserProfile(saved.getId(), saved.getEmail(), saved.getName(), saved.isActive(), saved.isEmailVerified(), saved.getLastLoginAt()));
    }

    @PutMapping("/admin/users/{userId}/deactivate")
    public ResponseEntity<Map<String, String>> adminDeactivateUser(@PathVariable UUID userId) {
        this.accountAdministrationService.deactivateUser(userId);
        return ResponseEntity.ok(Map.of("message", "User deactivated"));
    }

    @PutMapping("/admin/users/{userId}/reactivate")
    public ResponseEntity<Map<String, String>> adminReactivateUser(@PathVariable UUID userId) {
        this.accountAdministrationService.reactivateUser(userId);
        return ResponseEntity.ok(Map.of("message", "User reactivated"));
    }

    @PutMapping("/admin/users/{userId}/password")
    public ResponseEntity<Map<String, String>> adminResetPassword(@PathVariable UUID userId, @RequestBody ResetPasswordRequest req) {
        this.passwordResetService.adminResetPassword(userId, req.password());
        return ResponseEntity.ok(Map.of("message", "Password reset"));
    }

    @PutMapping("/admin/tenants/{tenantId}/roles/{userId}")
    public ResponseEntity<Map<String, String>> adminAssignTenantRole(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @RequestBody AssignTenantRoleRequest req) {
        this.roleManagementService.assignTenantRole(userId, tenantId, TenantRole.from(req.role()));
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
    public ResponseEntity<Map<String, String>> adminGrantProjectRole(@PathVariable String projectId, @RequestBody AssignMemberRequest req) {
        this.accessControlService.grantProjectRole(projectId, req.userId(), ProjectRole.from(req.role()));
        return ResponseEntity.ok(Map.of("message", "Project role granted"));
    }

    @DeleteMapping("/admin/projects/{projectId}/members/{userId}")
    public ResponseEntity<Map<String, String>> adminRevokeProjectRole(@PathVariable String projectId, @PathVariable UUID userId) {
        this.accessControlService.revokeProjectRole(projectId, userId);
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

    public record UserProfile(UUID id, String email, String name, boolean active, boolean emailVerified, OffsetDateTime lastLoginAt) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
    public record MyProjectResponse(String projectId, String role, OffsetDateTime assignedAt) {}
    public record CreateUserRequest(String email, String name, String password, UUID tenantId, String tenantRole) {}
    public record ResetPasswordRequest(String password) {}
    public record AssignTenantRoleRequest(String role) {}
    public record AssignMemberRequest(UUID userId, String role) {}
    public record MemberResponse(UUID userId, String projectId, String email, String name, String role) {}
    public record PasswordResetRequest(String email) {}
    public record CompletePasswordResetRequest(String token, String newPassword) {}
    public record PasswordResetTokenResponse(String message, String token) {}
}
