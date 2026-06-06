package org.eclipse.syson.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.eclipse.syson.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private static final Logger LOG = LoggerFactory.getLogger(UserController.class);

    private final UserRepository userRepository;
    private final ProjectAccessService projectAccessService;
    private final PasswordEncoder passwordEncoder;
    private final MembershipRepository membershipRepository;

    public UserController(UserRepository userRepository,
                          ProjectAccessService projectAccessService,
                          PasswordEncoder passwordEncoder,
                          MembershipRepository membershipRepository) {
        this.userRepository = userRepository;
        this.projectAccessService = projectAccessService;
        this.passwordEncoder = passwordEncoder;
        this.membershipRepository = membershipRepository;
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
        return new UserProfile(u.getId(), u.getEmail(), u.getName(), u.isActive());
    }

    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ChangePasswordRequest req) {
        SysonUser user = this.currentUser();
        if (!this.passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        user.setPasswordHash(this.passwordEncoder.encode(req.newPassword()));
        user.setUpdatedAt(OffsetDateTime.now());
        this.userRepository.save(user);
        LOG.info("User {} changed password", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password changed"));
    }

    @GetMapping("/me/projects")
    public List<MyProjectResponse> myProjects() {
        UUID uid = TenantContext.getUserIdAsUuid();
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return this.projectAccessService.getMyProjects().stream()
                .map(m -> new MyProjectResponse(m.getId().getProjectId(), m.getRole(), m.getCreatedAt()))
                .toList();
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("user", "ok");
    }

    // ── Admin endpoints ───────────────────────────────────────────────────

    @GetMapping("/admin/users")
    public List<SysonUser> adminListUsers() {
        return this.userRepository.findAll();
    }

    @PostMapping("/admin/users")
    public ResponseEntity<SysonUser> adminCreateUser(@RequestBody CreateUserRequest req) {
        if (this.userRepository.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
        }
        SysonUser user = new SysonUser();
        user.setEmail(req.email());
        user.setName(req.name());
        user.setPasswordHash(this.passwordEncoder.encode(req.password()));
        user.setActive(true);
        user.setFailedLoginAttempts(0);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        SysonUser saved = this.userRepository.save(user);
        LOG.info("Admin created user: {}", saved.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/admin/users/{userId}/password")
    public ResponseEntity<Map<String, String>> adminResetPassword(
            @PathVariable UUID userId, @RequestBody ResetPasswordRequest req) {
        SysonUser user = this.userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPasswordHash(this.passwordEncoder.encode(req.password()));
        user.setUpdatedAt(OffsetDateTime.now());
        this.userRepository.save(user);
        LOG.info("Admin reset password for user: {}", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password reset"));
    }

    @GetMapping("/admin/projects/{projectId}/members")
    public List<MemberResponse> adminProjectMembers(@PathVariable String projectId) {
        return this.projectAccessService.getProjectMembers(projectId).stream()
                .map(m -> {
                    SysonUser u = this.userRepository.findById(m.getId().getUserId()).orElse(null);
                    return new MemberResponse(
                            m.getId().getUserId(), m.getId().getProjectId(),
                            u != null ? u.getEmail() : "unknown",
                            u != null ? u.getName() : "Unknown",
                            m.getRole());
                })
                .toList();
    }

    @PostMapping("/admin/projects/{projectId}/members")
    public ResponseEntity<Map<String, String>> adminAssignUser(
            @PathVariable String projectId, @RequestBody AssignMemberRequest req) {
        this.projectAccessService.assignUserToProject(projectId, req.userId(), req.role());
        LOG.info("Assigned user {} to project {} as {}", req.userId(), projectId, req.role());
        return ResponseEntity.ok(Map.of("message", "Member assigned"));
    }

    @DeleteMapping("/admin/projects/{projectId}/members/{userId}")
    public ResponseEntity<Map<String, String>> adminRemoveUser(
            @PathVariable String projectId, @PathVariable UUID userId) {
        this.projectAccessService.removeUserFromProject(projectId, userId);
        LOG.info("Removed user {} from project {}", userId, projectId);
        return ResponseEntity.ok(Map.of("message", "Member removed"));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    public record UserProfile(UUID id, String email, String name, boolean active) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
    public record MyProjectResponse(String projectId, String role, OffsetDateTime assignedAt) {}
    public record CreateUserRequest(String email, String name, String password) {}
    public record ResetPasswordRequest(String password) {}
    public record AssignMemberRequest(UUID userId, String role) {}
    public record MemberResponse(UUID userId, String projectId, String email, String name, String role) {}
}
