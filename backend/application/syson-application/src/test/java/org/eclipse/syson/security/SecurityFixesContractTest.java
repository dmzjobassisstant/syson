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
package org.eclipse.syson.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Reflection- and source-level contract tests for the June 2026 SysON security and race-condition hardening.
 *
 * <p>
 * These tests intentionally avoid booting Spring/Sirius Web. They verify the structural contracts of the
 * fixes through reflection over the compiled classes and, where the security guarantee is a source-level
 * invariant (e.g. "this branch must check for SUPERUSER"), by reading the production source from disk.
 * </p>
 *
 * <h3>Coverage</h3>
 * <ul>
 *   <li>SecurityConfig enables method-level security.</li>
 *   <li>VersionControlController is authorization-aware and derives tenant/user identity server-side.</li>
 *   <li>AccessControlService grants a global bypass only to SUPERUSER (not ADMIN).</li>
 *   <li>UserController no longer leaks reset tokens and scopes audit events per tenant.</li>
 *   <li>Pessimistic-lock queries protect concurrent commit numbering and lock acquisition.</li>
 *   <li>Head/Branch entities are aligned to their real database columns.</li>
 *   <li>The V20 migration restores missing hot-path indexes.</li>
 * </ul>
 *
 * @author syson-team
 */
@DisplayName("Security and Race Condition Fixes Contract Tests")
public class SecurityFixesContractTest {

    private static final String ACL_FQN = "org.eclipse.syson.auth.service.AccessControlService";

    private static final String SECURITY_CONFIG_FQN = "org.eclipse.syson.auth.SecurityConfig";

    private static final String VC_CONTROLLER_FQN = "org.eclipse.syson.vc.VersionControlController";

    private static final String USER_CONTROLLER_FQN = "org.eclipse.syson.auth.UserController";

    private static final Pattern METHOD_BOUNDARY = Pattern.compile("\\n {4}(private |public |protected |@)");

    // ──────────────────────────────────────────────────────────────────────
    // 1. SecurityConfig has @EnableMethodSecurity
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("1. SecurityConfig enables method-level security (@EnableMethodSecurity)")
    @Test
    public void securityConfigEnablesMethodSecurity() throws Exception {
        Class<?> securityConfig = this.requiredClass(SECURITY_CONFIG_FQN);

        assertThat(securityConfig.isAnnotationPresent(EnableMethodSecurity.class))
                .as("@EnableMethodSecurity must be present so @PreAuthorize/@PostAuthorize is actually enforced")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. VersionControlController wires AccessControlService
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("2. VersionControlController injects AccessControlService for authorization")
    @Test
    public void versionControlControllerWiresAccessControlService() throws Exception {
        Class<?> controller = this.requiredClass(VC_CONTROLLER_FQN);

        boolean aclField = Arrays.stream(controller.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .anyMatch(ACL_FQN::equals);

        boolean aclCtorParam = Arrays.stream(controller.getDeclaredConstructors())
                .flatMap(ctor -> Arrays.stream(ctor.getParameterTypes()))
                .map(Class::getName)
                .anyMatch(ACL_FQN::equals);

        assertThat(aclField || aclCtorParam)
                .as("VersionControlController must wire AccessControlService (as a field or constructor parameter)")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. VersionControlController.getBranches no longer takes tenantId @RequestParam
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("3. getBranches derives tenantId server-side (no client-supplied tenantId param)")
    @Test
    public void getBranchesDoesNotAcceptTenantIdParam() throws Exception {
        Class<?> controller = this.requiredClass(VC_CONTROLLER_FQN);
        Method getBranches = this.requiredMethod(controller, "getBranches");

        // The fix removed the client-supplied tenantId; the endpoint must now accept only projectId.
        // Parameter count is checked (not just names) because reflection names require the
        // -parameters compile flag and otherwise degrade to arg0/arg1.
        assertThat(getBranches.getParameterCount())
                .as("getBranches must accept only projectId (tenantId removed) — found %s parameters",
                        getBranches.getParameterCount())
                .isEqualTo(1);
        assertThat(getBranches.getParameterTypes()[0])
                .as("getBranches' single parameter must be java.util.UUID")
                .isEqualTo(UUID.class);

        // Defensive: regardless of -parameters availability, no parameter may be named tenantId.
        assertThat(Arrays.stream(getBranches.getParameters()).map(Parameter::getName))
                .as("getBranches must not expose a tenantId parameter")
                .doesNotContain("tenantId");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. AccessControlService.hasProjectRole only bypasses for SUPERUSER
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("4. AccessControlService.hasProjectRole bypasses ONLY for SUPERUSER")
    @Test
    public void accessControlBypassOnlySuperuser() throws Exception {
        this.requiredClass("org.eclipse.syson.auth.service.AccessControlService");
        String source = this.readSource("org/eclipse/syson/auth/service/AccessControlService.java");

        String hasProjectRole = this.extractMethodRegion(source, "private boolean hasProjectRole(");

        assertThat(hasProjectRole)
                .as("hasProjectRole must reference SUPERUSER for the global bypass")
                .contains("SUPERUSER");
        assertThat(hasProjectRole)
                .as("hasProjectRole must NOT bypass for TenantRole.ADMIN (cross-tenant privilege escalation risk)")
                .doesNotContain("TenantRole.ADMIN");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5. UserController.requestPasswordReset doesn't return the token
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("5. requestPasswordReset does not leak the reset token in its response")
    @Test
    public void requestPasswordResetDoesNotReturnToken() throws Exception {
        Class<?> controller = this.requiredClass(USER_CONTROLLER_FQN);
        Method requestPasswordReset = this.requiredMethod(controller, "requestPasswordReset");

        String returnSimple = requestPasswordReset.getReturnType().getSimpleName();

        assertThat(returnSimple)
                .as("requestPasswordReset must NOT return PasswordResetTokenResponse (token disclosure)")
                .isNotEqualTo("PasswordResetTokenResponse");
        assertThat(returnSimple)
                .as("requestPasswordReset should return a generic ResponseEntity envelope")
                .isEqualTo("ResponseEntity");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 6. UserController audit events are tenant-scoped
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("6. UserController scopes audit events to the caller's tenant")
    @Test
    public void auditEventsAreTenantScoped() throws Exception {
        String source = this.readSource("org/eclipse/syson/auth/UserController.java");

        String auditMethod = this.extractMethodRegion(source, "public List<AuditEvent> adminAuditEvents(");

        assertThat(auditMethod)
                .as("Non-superuser audit queries must be filtered by TenantContext.getTenantId()")
                .contains("TenantContext.getTenantId()");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 7. UserController prevents non-SUPERUSER from assigning SUPERUSER role
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("7. assignTenantRole blocks non-SUPERUSER from granting the SUPERUSER role")
    @Test
    public void assignTenantRoleGuardsSuperuserEscalation() throws Exception {
        String source = this.readSource("org/eclipse/syson/auth/UserController.java");

        String assignMethod = this.extractMethodRegion(source,
                "public ResponseEntity<Map<String, String>> adminAssignTenantRole(");

        assertThat(assignMethod)
                .as("adminAssignTenantRole must guard SUPERUSER escalation (reference SUPERUSER / superuser check)")
                .contains("SUPERUSER");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 8. CommitRepository has pessimistic lock query
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("8. CommitRepository exposes a pessimistic-lock query for commit numbering")
    @Test
    public void commitRepositoryHasPessimisticLockQuery() throws Exception {
        Class<?> repo = this.requiredClass("org.eclipse.syson.vc.repository.CommitRepository");

        Method forUpdate = repo.getDeclaredMethod(
                "findTopByProjectIdAndBranchIdOrderByCommitNumberDescForUpdate",
                UUID.class, UUID.class);

        assertThat(forUpdate.isAnnotationPresent(Lock.class))
                .as("findTopByProjectIdAndBranchIdOrderByCommitNumberDescForUpdate must carry @Lock(PESSIMISTIC_WRITE)")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 9. BranchLockRepository has pessimistic lock query
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("9. BranchLockRepository exposes a pessimistic-lock query for lock acquisition")
    @Test
    public void branchLockRepositoryHasPessimisticLockQuery() throws Exception {
        Class<?> repo = this.requiredClass("org.eclipse.syson.locks.repository.BranchLockRepository");

        Method forUpdate = repo.getDeclaredMethod(
                "findByProjectIdAndBranchIdAndLockTypeForUpdate",
                String.class, UUID.class, String.class);

        assertThat(forUpdate.isAnnotationPresent(Lock.class))
                .as("findByProjectIdAndBranchIdAndLockTypeForUpdate must carry @Lock(PESSIMISTIC_WRITE)")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 10. ElementLockRepository has pessimistic lock query
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("10. ElementLockRepository exposes a pessimistic-lock query for element lock acquisition")
    @Test
    public void elementLockRepositoryHasPessimisticLockQuery() throws Exception {
        Class<?> repo = this.requiredClass("org.eclipse.syson.locks.repository.ElementLockRepository");

        Method forUpdate = repo.getDeclaredMethod(
                "findByProjectIdAndBranchIdAndStableIdAndLockTypeForUpdate",
                String.class, UUID.class, String.class, String.class);

        assertThat(forUpdate.isAnnotationPresent(Lock.class))
                .as("findByProjectIdAndBranchIdAndStableIdAndLockTypeForUpdate must carry @Lock(PESSIMISTIC_WRITE)")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 11. HeadElement entity uses owner_stable_id not owner_id
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("11. HeadElement column aligned to owner_stable_id (not owner_id)")
    @Test
    public void headElementUsesOwnerStableIdColumn() throws Exception {
        String source = this.readSource("org/eclipse/syson/history/entity/HeadElement.java");

        assertThat(source)
                .as("HeadElement must map the owner_stable_id column")
                .contains("owner_stable_id");
        assertThat(source)
                .as("HeadElement must NOT map the legacy owner_id column")
                .doesNotContain("name = \"owner_id\"");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 12. HeadRelationship entity uses source_stable_id not source_id
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("12. HeadRelationship column aligned to source_stable_id")
    @Test
    public void headRelationshipUsesSourceStableIdColumn() throws Exception {
        String source = this.readSource("org/eclipse/syson/history/entity/HeadRelationship.java");

        assertThat(source)
                .as("HeadRelationship must map the source_stable_id column")
                .contains("source_stable_id");
        assertThat(source)
                .as("HeadRelationship must NOT map the legacy source_id column")
                .doesNotContain("name = \"source_id\"");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 13. BranchHead entity no longer has semantic_data_id column
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("13. BranchHead no longer maps a semantic_data_id column")
    @Test
    public void branchHeadHasNoSemanticDataIdColumn() throws Exception {
        String source = this.readSource("org/eclipse/syson/history/entity/BranchHead.java");

        assertThat(source)
                .as("BranchHead must NOT map the removed semantic_data_id column")
                .doesNotContain("semantic_data_id");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 14. V20 migration file exists and indexes syson_changes(commit_id)
    // ──────────────────────────────────────────────────────────────────────

    @DisplayName("14. V20 migration adds the missing commit_id index on syson_changes")
    @Test
    public void v20MigrationAddsCommitIdIndex() throws Exception {
        Path migration = Paths.get("src/main/resources/db/migration/V20__add_missing_indexes.sql");

        assertThat(Files.exists(migration))
                .as("V20__add_missing_indexes.sql migration must exist")
                .isTrue();

        String sql = Files.readString(migration);

        assertThat(sql)
                .as("V20 must contain a CREATE INDEX statement")
                .contains("CREATE INDEX");
        assertThat(sql)
                .as("V20 must index syson_changes(commit_id) for the hot-path change join")
                .contains("syson_changes");
        assertThat(sql)
                .as("The new index must target the commit_id column")
                .contains("commit_id");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Loads a class by fully-qualified name, failing the test with a clear message if it is absent.
     */
    private Class<?> requiredClass(String fqn) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Missing security contract class: " + fqn, exception);
        }
    }

    /**
     * Finds the first declared method with the given name, failing if none exists.
     */
    private Method requiredMethod(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected method '" + name + "' on " + type.getName()));
    }

    /**
     * Reads a production source file relative to {@code src/main/java}.
     *
     * <p>Resolves against the module working directory (Maven Surefire runs in the module root), so
     * {@code src/main/java} is the standard base.</p>
     */
    private String readSource(String packageRelativePath) throws Exception {
        Path source = Paths.get("src/main/java").resolve(packageRelativePath);
        assertThat(Files.exists(source))
                .as("Source file not found: %s (working directory must be the syson-application module)", source)
                .isTrue();
        return Files.readString(source);
    }

    /**
     * Extracts the source region of a single method, from its definition line up to the next member
     * boundary (a field/method/annotation declared at the 4-space class-body indent).
     *
     * <p>This isolates the method body so that source-level assertions ("must check for X") are scoped
     * to the right method instead of the whole compilation unit.</p>
     */
    private String extractMethodRegion(String source, String definitionSignature) {
        int start = source.indexOf(definitionSignature);
        assertThat(start)
                .as("Expected method definition '%s' in source", definitionSignature)
                .isGreaterThan(-1);

        int searchFrom = start + definitionSignature.length();
        Matcher boundary = METHOD_BOUNDARY.matcher(source).region(searchFrom, source.length());
        int end = boundary.find() ? boundary.start() : source.length();

        return source.substring(start, end);
    }
}
