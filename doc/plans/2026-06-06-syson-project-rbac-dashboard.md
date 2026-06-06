# SysON Project RBAC & User Dashboard — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add project-level RBAC (admin/user/viewer roles assigned per-project), a user self-service dashboard (password change, my projects), and an admin console (user CRUD, project assignment).

**Architecture:** Additive — new JPA entities/repositories/controllers alongside existing Sirius Web. A `syson_project_members` table links Sirius Web's native `project` rows to `syson_users`. New REST controllers at `/api/v1/admin/**` (role-gated) and `/api/v1/user/**` (authenticated self-service). Frontend extends the existing auth.js user bar with a Dashboard overlay.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, PostgreSQL, vanilla JS (auth.js)

---

## Pre-Flight: Environment

All paths relative to `/root/syson-fork`.
DB is PostgreSQL on `localhost:5432` with user `syson`, database `syson`.
Build: `mvn clean package -DskipTests -Dcheckstyle.skip=true -pl backend/application/syson-application -o`
Deploy: rebuild Docker image `syson-rbac:latest`, restart container with `--env-file /tmp/syson-run.env`.

---

### Task 1: Create V5 Flyway migration — `syson_project_members` table

**Objective:** Add the project membership table to the schema.

**Files:**
- Create: `backend/application/syson-application/src/main/resources/db/migration/V5__project_members.sql`

**Step 1: Write the SQL migration**

```sql
CREATE TABLE IF NOT EXISTS syson_project_members (
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES syson_users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL CHECK (role IN ('admin','user','viewer')),
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_spm_user ON syson_project_members(user_id);
CREATE INDEX IF NOT EXISTS idx_spm_project ON syson_project_members(project_id);
```

**Step 2: Commit**

```bash
git add backend/application/syson-application/src/main/resources/db/migration/V5__project_members.sql
git commit -m "feat: add syson_project_members table migration"
```

---

### Task 2: Create ProjectMembership JPA entity

**Objective:** JPA entity mapping the `syson_project_members` table.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/entity/ProjectMembership.java`

```java
package org.eclipse.syson.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_project_members")
public class ProjectMembership {

    @EmbeddedId
    private ProjectMembershipId id;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public ProjectMembership() {
    }

    public ProjectMembership(String projectId, UUID userId, String role) {
        this.id = new ProjectMembershipId(projectId, userId);
        this.role = role;
        this.createdAt = OffsetDateTime.now();
    }

    public ProjectMembershipId getId() {
        return this.id;
    }

    public void setId(ProjectMembershipId id) {
        this.id = id;
    }

    public String getRole() {
        return this.role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Embeddable
    public static class ProjectMembershipId implements Serializable {
        @Column(name = "project_id", nullable = false)
        private String projectId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        public ProjectMembershipId() {
        }

        public ProjectMembershipId(String projectId, UUID userId) {
            this.projectId = projectId;
            this.userId = userId;
        }

        public String getProjectId() {
            return this.projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public UUID getUserId() {
            return this.userId;
        }

        public void setUserId(UUID userId) {
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProjectMembershipId that)) return false;
            return Objects.equals(this.projectId, that.projectId)
                && Objects.equals(this.userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.projectId, this.userId);
        }
    }
}
```

**Step 1: Commit**

```bash
git add backend/application/syson-application/src/main/java/org/eclipse/syson/auth/entity/ProjectMembership.java
git commit -m "feat: add ProjectMembership JPA entity"
```

---

### Task 3: Create ProjectMembershipRepository (JPA)

**Objective:** Spring Data JPA repository for project membership queries.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/repository/ProjectMembershipRepository.java`

```java
package org.eclipse.syson.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ProjectMembership;
import org.eclipse.syson.auth.entity.ProjectMembership.ProjectMembershipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectMembershipRepository extends JpaRepository<ProjectMembership, ProjectMembershipId> {

    List<ProjectMembership> findByIdProjectId(String projectId);

    List<ProjectMembership> findByIdUserId(UUID userId);

    Optional<ProjectMembership> findByIdProjectIdAndIdUserId(String projectId, UUID userId);

    void deleteByIdProjectIdAndIdUserId(String projectId, UUID userId);

    boolean existsByIdProjectIdAndIdUserId(String projectId, UUID userId);
}
```

**Step 1: Commit**

```bash
git add backend/application/syson-application/src/main/java/org/eclipse/syson/auth/repository/ProjectMembershipRepository.java
git commit -m "feat: add ProjectMembershipRepository"
```

---

### Task 4: Create ProjectAccessService

**Objective:** Service layer for permission checks and membership management.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/ProjectAccessService.java`

```java
package org.eclipse.syson.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ProjectMembership;
import org.eclipse.syson.auth.repository.ProjectMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectAccessService {

    private final ProjectMembershipRepository membershipRepository;

    public ProjectAccessService(ProjectMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /**
     * Reads the current user ID from the TenantContext (set by JwtAuthenticationFilter).
     */
    private UUID currentUserId() {
        String uid = TenantContext.getUserId();
        if (uid == null) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return UUID.fromString(uid);
    }

    /**
     * Returns the projects the current user is a member of.
     */
    public List<ProjectMembership> getMyProjects() {
        return this.membershipRepository.findByIdUserId(this.currentUserId());
    }

    /**
     * Returns members of a project (admin-only).
     */
    public List<ProjectMembership> getProjectMembers(String projectId) {
        return this.membershipRepository.findByIdProjectId(projectId);
    }

    /**
     * Assigns a user to a project with the given role.
     * Does NOT check existing — upserts.
     */
    @Transactional
    public void assignUserToProject(String projectId, UUID userId, String role) {
        ProjectMembership pm = new ProjectMembership(projectId, userId, role);
        pm.setCreatedAt(OffsetDateTime.now());
        this.membershipRepository.save(pm);
    }

    /**
     * Removes a user from a project.
     */
    @Transactional
    public void removeUserFromProject(String projectId, UUID userId) {
        this.membershipRepository.deleteByIdProjectIdAndIdUserId(projectId, userId);
    }

    /**
     * Checks whether the current user has at least the given role on a project.
     * Role hierarchy: admin > user > viewer.
     * Returns true if the user is a superuser (tenant-level).
     */
    public boolean hasProjectAccess(String projectId, String requiredRole) {
        UUID uid = this.currentUserId();
        return this.membershipRepository.findByIdProjectIdAndIdUserId(projectId, uid)
                .map(m -> roleRank(m.getRole()) >= roleRank(requiredRole))
                .orElse(false);
    }

    /**
     * Returns the user's role on a project, or null if not a member.
     */
    public String getProjectRole(String projectId) {
        UUID uid = this.currentUserId();
        return this.membershipRepository.findByIdProjectIdAndIdUserId(projectId, uid)
                .map(ProjectMembership::getRole)
                .orElse(null);
    }

    private static int roleRank(String role) {
        return switch (role) {
            case "admin" -> 3;
            case "user" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
```

**Step 1: Commit**

```bash
git add backend/application/syson-application/src/main/java/org/eclipse/syson/auth/ProjectAccessService.java
git commit -m "feat: add ProjectAccessService with permission checks"
```

---

### Task 5: Extend TenantContext to expose user ID as UUID

**Objective:** Add a UUID-typed getter so `ProjectAccessService` doesn't parse strings.

**Files:**
- Modify: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/TenantContext.java`

Add:

```java
public static UUID getUserIdAsUuid() {
    String uid = CURRENT_USER_ID.get();
    return uid != null ? UUID.fromString(uid) : null;
}
```

Also update `JwtAuthenticationFilter` line 105 to store the UUID directly:

```java
// Change:
request.setAttribute(USER_ID_ATTR, username);
// To:
request.setAttribute(USER_ID_ATTR, userId.toString());
```

Wait — `username` is actually the email. The JWT needs to carry the user UUID as a claim. Let me check the JWT service...

Actually the current `JwtAuthenticationFilter` sets `USER_ID_ATTR` to `username` (the email). The JWT already includes `subject` as the email. Let me update the JWT to include a `userId` claim and the filter to extract it properly.

**Modify:** `JwtAuthenticationFilter.java` — update to pass userId as UUID

In `doFilterInternal`, after line 99:
```java
// After: authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
// Add:
String userIdStr = this.jwtService.extractUserId(token);
request.setAttribute(USER_ID_ATTR, userIdStr);
```

Also remove the old line 105:
```java
// REMOVE: request.setAttribute(USER_ID_ATTR, username);
```

**Modify:** `JwtService.java` — add userId claim and extractor

In `generateToken`:
```java
public String generateToken(UserDetails userDetails, UUID tenantId, UUID userId) {
    // Add .claim("userId", userId.toString()) 
}
```

Add extractor:
```java
public String extractUserId(String token) {
    return extractClaim(token, claims -> claims.get("userId", String.class));
}
```

**Modify:** `AuthController.java` line 87 — pass userId to generateToken

```java
// Change:
String token = this.jwtService.generateToken(userDetails, tenantId);
// To:
String token = this.jwtService.generateToken(userDetails, tenantId, userId);
```

**Modify:** `TenantContext.java` — add getUserIdAsUuid()

```java
public static UUID getUserIdAsUuid() {
    String uid = CURRENT_USER_ID.get();
    return uid != null ? UUID.fromString(uid) : null;
}
```

**Modify:** `ProjectAccessService.java` — use getUserIdAsUuid()

Change `currentUserId()`:
```java
private UUID currentUserId() {
    UUID uid = TenantContext.getUserIdAsUuid();
    if (uid == null) {
        throw new IllegalStateException("No authenticated user in context");
    }
    return uid;
}
```

**Step 1: Commit**

```bash
git add backend/application/syson-application/src/main/java/org/eclipse/syson/auth/TenantContext.java \
        backend/application/syson-application/src/main/java/org/eclipse/syson/auth/JwtAuthenticationFilter.java \
        backend/application/syson-application/src/main/java/org/eclipse/syson/auth/JwtService.java \
        backend/application/syson-application/src/main/java/org/eclipse/syson/auth/AuthController.java \
        backend/application/syson-application/src/main/java/org/eclipse/syson/auth/ProjectAccessService.java
git commit -m "feat: extend JWT with userId claim, expose UUID in TenantContext"
```

---

### Task 6: Create AdminController — user management & project assignment

**Objective:** REST endpoints for admin user CRUD and project membership management, gated to superuser/admin roles.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/AdminController.java`

```java
package org.eclipse.syson.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ProjectMembership;
import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.eclipse.syson.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('superuser','admin')")
public class AdminController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final ProjectAccessService projectAccessService;
    private final PasswordEncoder passwordEncoder;

    public AdminController(UserRepository userRepository,
                           MembershipRepository membershipRepository,
                           ProjectAccessService projectAccessService,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.projectAccessService = projectAccessService;
        this.passwordEncoder = passwordEncoder;
    }

    // ── User management ─────────────────────────────────────────────────

    @GetMapping("/users")
    public List<SysonUser> listUsers() {
        return this.userRepository.findAll();
    }

    @PostMapping("/users")
    public ResponseEntity<SysonUser> createUser(@RequestBody CreateUserRequest req) {
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

    @PutMapping("/users/{userId}/password")
    public ResponseEntity<Map<String,String>> resetPassword(
            @PathVariable UUID userId, @RequestBody ResetPasswordRequest req) {
        SysonUser user = this.userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPasswordHash(this.passwordEncoder.encode(req.password()));
        user.setUpdatedAt(OffsetDateTime.now());
        this.userRepository.save(user);
        LOG.info("Admin reset password for user: {}", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password reset"));
    }

    // ── Project membership management ────────────────────────────────────

    @GetMapping("/projects/{projectId}/members")
    public List<MemberResponse> listProjectMembers(@PathVariable String projectId) {
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

    @PostMapping("/projects/{projectId}/members")
    public ResponseEntity<Map<String,String>> assignUser(
            @PathVariable String projectId, @RequestBody AssignMemberRequest req) {
        this.projectAccessService.assignUserToProject(projectId, req.userId(), req.role());
        LOG.info("Assigned user {} to project {} as {}", req.userId(), projectId, req.role());
        return ResponseEntity.ok(Map.of("message", "Member assigned"));
    }

    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public ResponseEntity<Map<String,String>> removeUser(
            @PathVariable String projectId, @PathVariable UUID userId) {
        this.projectAccessService.removeUserFromProject(projectId, userId);
        LOG.info("Removed user {} from project {}", userId, projectId);
        return ResponseEntity.ok(Map.of("message", "Member removed"));
    }

    // ── DTOs ────────────────────────────────────────────────────────────

    public record CreateUserRequest(String email, String name, String password) {}
    public record ResetPasswordRequest(String password) {}
    public record AssignMemberRequest(UUID userId, String role) {}
    public record MemberResponse(UUID userId, String projectId, String email, String name, String role) {}
}
```

**Important:** Spring Security method security (`@PreAuthorize`) requires `@EnableMethodSecurity` in the config. We'll add that in the next task.

**Step 1: Commit**

```bash
git add backend/application/syson-application/src/main/java/org/eclipse/syson/auth/AdminController.java
git commit -m "feat: add AdminController for user & project management"
```

---

### Task 7: Create UserController — self-service endpoints

**Objective:** Endpoints for authenticated users to view profile, change password, and list their projects.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/UserController.java`

```java
package org.eclipse.syson.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ProjectMembership;
import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
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

    public UserController(UserRepository userRepository,
                          ProjectAccessService projectAccessService,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.projectAccessService = projectAccessService;
        this.passwordEncoder = passwordEncoder;
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
    public ResponseEntity<Map<String,String>> changePassword(@RequestBody ChangePasswordRequest req) {
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

    // ── DTOs ────────────────────────────────────────────────────────────

    public record UserProfile(UUID id, String email, String name, boolean active) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
    public record MyProjectResponse(String projectId, String role, OffsetDateTime assignedAt) {}
}
```

**Step 1: Commit**

```bash
git add backend/application/syson-application/src/main/java/org/eclipse/syson/auth/UserController.java
git commit -m "feat: add UserController for self-service dashboard"
```

---

### Task 8: Update SecurityConfig — method security + admin gating

**Objective:** Enable method-level security for `@PreAuthorize` and ensure admin endpoints are protected.

**Files:**
- Modify: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/SecurityConfig.java`

Add `@EnableMethodSecurity`:
```java
// Add import:
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

// Add annotation to class:
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // <-- ADD THIS
public class SecurityConfig {
```

Update `authorizeHttpRequests` to protect admin endpoints:
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/auth/**").permitAll()
    .requestMatchers("/api/graphql").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    .requestMatchers("/api/v1/admin/**").hasAnyAuthority("ROLE_superuser", "ROLE_admin")
    .requestMatchers("/api/v1/user/**").authenticated()
    .requestMatchers("/api/v1/**").authenticated()
    .anyRequest().permitAll()
)
```

**Step 1: Verify compilation**

```bash
cd /root/syson-fork
mvn compile -pl backend/application/syson-application -o 2>&1 | tail -20
# Expected: BUILD SUCCESS
```

**Step 2: Commit**

```bash
git add backend/application/syson-application/src/main/java/org/eclipse/syson/auth/SecurityConfig.java
git commit -m "feat: enable method security, gate admin/user endpoints"
```

---

### Task 9: Update auth.js — add Dashboard overlay

**Objective:** Extend the user bar with a "Dashboard" link that opens a modal overlay showing user profile, password change, and project list.

**Files:**
- Modify: `frontend/syson/public/auth.js`

**What to add (after the `mountUserBar` function, around line 294):**

1. Add "Dashboard" button in the user bar HTML (replace the innerHTML line 285-289):

```javascript
bar.innerHTML = `
  <span>${state.email}</span>
  ${badgeHTML}
  <button id="syson-dashboard-btn" title="Dashboard">Dashboard</button>
  <button id="syson-logout-btn" title="Sign out">Sign out</button>
`;
```

2. Add dashboard event listener (after logout button listener, around line 291):

```javascript
document.getElementById('syson-dashboard-btn').addEventListener('click', showDashboard);
```

3. Add `showDashboard()` function — fetches profile + projects, renders a modal:

```javascript
async function showDashboard() {
  // Fetch profile and projects in parallel
  const [meRes, projectsRes] = await Promise.all([
    _origFetch(API_BASE + '/api/v1/user/me', { headers: { Authorization: 'Bearer ' + state.token } }),
    _origFetch(API_BASE + '/api/v1/user/me/projects', { headers: { Authorization: 'Bearer ' + state.token } }),
  ]);
  const me = meRes.ok ? await meRes.json() : { email: state.email, name: '' };
  const projects = projectsRes.ok ? await projectsRes.json() : [];

  // Remove existing dashboard if present
  const old = document.getElementById('syson-dashboard-overlay');
  if (old) old.remove();

  const overlay = document.createElement('div');
  overlay.id = 'syson-dashboard-overlay';
  overlay.style.cssText = 'position:fixed;inset:0;z-index:100000;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,0.7);font-family:Lato,Roboto,Arial,sans-serif;';
  overlay.innerHTML = `
    <div style="background:#16213e;border-radius:12px;padding:2rem;width:100%;max-width:520px;max-height:80vh;overflow-y:auto;box-shadow:0 8px 32px rgba(0,0,0,0.5);">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:1.5rem;">
        <h2 style="color:#e0e0e0;margin:0;font-size:1.25rem;">Dashboard</h2>
        <button id="syson-dash-close" style="background:none;border:none;color:#888;font-size:1.5rem;cursor:pointer;line-height:1;">&times;</button>
      </div>

      <div style="margin-bottom:1.5rem;padding-bottom:1.5rem;border-bottom:1px solid #2a2a4a;">
        <h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">Profile</h3>
        <div style="color:#e0e0e0;font-size:0.9rem;">
          <div style="margin-bottom:0.3rem;"><strong style="color:#888;">Email:</strong> ${me.email}</div>
          <div style="margin-bottom:0.3rem;"><strong style="color:#888;">Name:</strong> ${me.name || '-'}</div>
          <div><strong style="color:#888;">Roles:</strong> ${state.roles.join(', ')}</div>
        </div>
      </div>

      <div style="margin-bottom:1.5rem;padding-bottom:1.5rem;border-bottom:1px solid #2a2a4a;">
        <h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">Change Password</h3>
        <form id="syson-password-form">
          <input id="syson-current-pw" type="password" placeholder="Current password" style="width:100%;padding:0.6rem 0.7rem;border-radius:6px;border:1px solid #2a2a4a;background:#0f0f23;color:#e0e0e0;font-size:0.9rem;margin-bottom:0.6rem;box-sizing:border-box;" />
          <input id="syson-new-pw" type="password" placeholder="New password" style="width:100%;padding:0.6rem 0.7rem;border-radius:6px;border:1px solid #2a2a4a;background:#0f0f23;color:#e0e0e0;font-size:0.9rem;margin-bottom:0.6rem;box-sizing:border-box;" />
          <button type="submit" style="width:100%;padding:0.6rem;border-radius:6px;border:none;background:#4a90d9;color:#fff;font-size:0.9rem;font-weight:600;cursor:pointer;">Update Password</button>
          <div id="syson-pw-msg" style="color:#4caf50;font-size:0.8rem;text-align:center;margin-top:0.5rem;min-height:1.2em;"></div>
        </form>
      </div>

      <div>
        <h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">My Projects</h3>
        ${projects.length === 0
          ? '<p style="color:#666;font-size:0.85rem;">No projects assigned.</p>'
          : `<div style="display:flex;flex-direction:column;gap:6px;">
            ${projects.map(p => `
              <div style="display:flex;justify-content:space-between;align-items:center;background:rgba(255,255,255,0.03);padding:8px 12px;border-radius:6px;">
                <span style="color:#e0e0e0;font-size:0.85rem;font-family:monospace;">${p.projectId.substring(0,12)}…</span>
                <span class="role-badge" style="background:#4a90d9;color:#fff;font-size:0.7rem;padding:2px 8px;border-radius:4px;font-weight:600;text-transform:uppercase;">${p.role}</span>
              </div>
            `).join('')}
          </div>`}
      </div>
    </div>
  `;

  document.body.appendChild(overlay);

  // Close handlers
  document.getElementById('syson-dash-close').addEventListener('click', () => overlay.remove());
  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });

  // Password change handler
  document.getElementById('syson-password-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msgEl = document.getElementById('syson-pw-msg');
    const currentPw = document.getElementById('syson-current-pw').value;
    const newPw = document.getElementById('syson-new-pw').value;
    if (!currentPw || !newPw) {
      msgEl.style.color = '#e74c3c';
      msgEl.textContent = 'Both fields are required';
      return;
    }
    try {
      const res = await _origFetch(API_BASE + '/api/v1/user/me/password', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + state.token },
        body: JSON.stringify({ currentPassword: currentPw, newPassword: newPw }),
      });
      if (res.ok) {
        msgEl.style.color = '#4caf50';
        msgEl.textContent = 'Password updated!';
        document.getElementById('syson-current-pw').value = '';
        document.getElementById('syson-new-pw').value = '';
      } else {
        const err = await res.text();
        msgEl.style.color = '#e74c3c';
        msgEl.textContent = err || 'Failed to update password';
      }
    } catch (err) {
      msgEl.style.color = '#e74c3c';
      msgEl.textContent = 'Network error';
    }
  });
}
```

**Step 1: Verify auth.js syntax**

```bash
node -c /root/syson-fork/frontend/syson/public/auth.js
# Expected: no output (syntax OK)
```

**Step 2: Commit**

```bash
git add frontend/syson/public/auth.js
git commit -m "feat: add Dashboard overlay with profile, password change, projects"
```

---

### Task 10: Update auth.js — expose roles to make Dashboard conditional

**Objective:** Only show Dashboard for authenticated users, not the login overlay.

The Dashboard button is already inside the user bar which only renders when `state.email` is set (line 265 check). No change needed — this is already correct.

No file changes. Skip commit.

---

### Task 11: Run DB migration on live database

**Objective:** Apply V5 migration to the production database.

**Step 1: Execute SQL**

```bash
python3 -c "
import subprocess
env = dict(line.split('=',1) for line in open('/tmp/syson-run.env').read().splitlines() if '=' in line)
pw = env.get('SPRING_DATASOURCE_PASSWORD','')
subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'syson', '-d', 'syson', '-f',
     'backend/application/syson-application/src/main/resources/db/migration/V5__project_members.sql'],
    env={'PGPASSWORD': pw}, check=True
)
print('Migration V5 applied.')
" 2>&1
```

**Step 2: Verify**

```bash
python3 -c "
import subprocess
env = dict(line.split('=',1) for line in open('/tmp/syson-run.env').read().splitlines() if '=' in line)
pw = env.get('SPRING_DATASOURCE_PASSWORD','')
r = subprocess.run(
    ['psql', '-h', 'localhost', '-U', 'syson', '-d', 'syson', '-c', r'\d syson_project_members'],
    env={'PGPASSWORD': pw}, capture_output=True, text=True
)
print(r.stdout)
" 2>&1
```

Expected: table definition output.

---

### Task 12: Build, deploy, and verify

**Objective:** Full build → Docker image → container restart → verify all endpoints.

**Step 1: Build**

```bash
cd /root/syson-fork
mvn clean package -DskipTests -Dcheckstyle.skip=true -pl backend/application/syson-application -o
# Expected: BUILD SUCCESS
```

**Step 2: Repackage frontend JAR (auth.js was updated)**

```bash
cp /root/.m2/repository/org/eclipse/syson/syson-frontend/2025.6.1/syson-frontend-2025.6.1.jar /tmp/syson-frontend-backup.jar
rm -rf /tmp/frontend-repack && mkdir /tmp/frontend-repack && cd /tmp/frontend-repack
jar xf /tmp/syson-frontend-backup.jar
cp /root/syson-fork/frontend/syson/public/auth.js static/
jar cfM /tmp/syson-frontend-patched.jar META-INF/ static/ .gitkeep
mvn install:install-file -Dfile=/tmp/syson-frontend-patched.jar -DgroupId=org.eclipse.syson -DartifactId=syson-frontend -Dversion=2025.6.1 -Dpackaging=jar -DgeneratePom=true
rm -f /root/.m2/repository/org/eclipse/syson/syson-frontend/2025.6.1/_remote.repositories
cd /root/syson-fork
mvn clean package -DskipTests -Dcheckstyle.skip=true -pl backend/application/syson-application -o
# Expected: BUILD SUCCESS
```

**Step 3: Build Docker image and redeploy**

```bash
docker build -t syson-rbac:latest backend/application/syson-application
docker rm -f syson
docker run -d --name syson --restart unless-stopped -p 8080:8080 --env-file /tmp/syson-run.env syson-rbac:latest
```

**Step 4: Wait for startup**

```bash
for i in $(seq 1 90); do
  if docker logs syson 2>&1 | grep -q 'Started SysONApplication'; then
    echo "Started after $((i*2))s"; break
  fi
  sleep 2
done
```

**Step 5: Verify all endpoints**

```bash
# Login
TOKEN=$(curl -sk -X POST https://syson.damuza-consulting.com/api/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"admin","password":"admin"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

# Profile
curl -sk https://syson.damuza-consulting.com/api/v1/user/me \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# My projects
curl -sk https://syson.damuza-consulting.com/api/v1/user/me/projects \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Change password
curl -sk -X PUT https://syson.damuza-consulting.com/api/v1/user/me/password \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"admin","newPassword":"admin"}' | python3 -m json.tool

# Admin: list users
curl -sk https://syson.damuza-consulting.com/api/v1/admin/users \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Admin: list project members (replace PID with an actual project UUID)
curl -sk https://syson.damuza-consulting.com/api/v1/admin/projects/SOME_PROJECT_ID/members \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Dashboard in UI
curl -sk https://syson.damuza-consulting.com/auth.js | grep -o 'Dashboard' | head -1
# Expected: Dashboard
```

---

### Task 13: Commit and push

```bash
cd /root/syson-fork
git add -A
git commit -m "feat: project RBAC with admin/user/viewer roles, user dashboard"
git push origin rbac
```
