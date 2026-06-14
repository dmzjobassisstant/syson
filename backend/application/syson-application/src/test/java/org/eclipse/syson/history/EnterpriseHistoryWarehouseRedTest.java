/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED contract tests for enterprise history, warehouse, and lock services.
 *
 * <p>
 * These tests intentionally use reflection so that they compile quickly without a
 * full Spring Boot boot. They should stay red until the enterprise history services
 * are implemented with the expected API surface.
 * </p>
 *
 * @author syson-team
 */
public class EnterpriseHistoryWarehouseRedTest {

    private static final String UUID = "UUID";

    private static final String STRING = "String";

    private static final String LIST = "List";

    private static final String MAP = "Map";

    // -- History service FQN constants --

    private static final String HISTORY_PKG = "org.eclipse.syson.history.service";

    private static final String ENTITY_PKG = "org.eclipse.syson.history.entity";

    private static final String REPO_PKG = "org.eclipse.syson.history.repository";

    private static final String LOCKS_SERVICE_PKG = "org.eclipse.syson.locks.service";

    private static final String LOCKS_ENTITY_PKG = "org.eclipse.syson.locks.entity";

    private static final String LOCKS_REPO_PKG = "org.eclipse.syson.locks.repository";

    // ========================================================================
    // 1. StableSysmlIdService
    // ========================================================================

    @DisplayName("StableSysmlIdService exists with stableIdFor(String,String,String,String) returning String")
    @Test
    public void stableSysmlIdServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".StableSysmlIdService");

        this.assertHasMethod(service, "stableIdFor", STRING, STRING, STRING, STRING);
    }

    // ========================================================================
    // 2. SysmlObjectHasher
    // ========================================================================

    @DisplayName("SysmlObjectHasher exists with hashObject(String) and canonicalizeJson(Map)")
    @Test
    public void sysmlObjectHasherContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".SysmlObjectHasher");

        this.assertHasMethod(service, "hashObject", STRING);
        this.assertHasMethod(service, "canonicalizeJson", MAP);
    }

    // ========================================================================
    // 3. SysmlCanonicalExtractor - inner records
    // ========================================================================

    @DisplayName("SysmlCanonicalExtractor exists with inner records CanonicalModelSnapshot, CanonicalElement, CanonicalRelationship")
    @Test
    public void sysmlCanonicalExtractorContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".SysmlCanonicalExtractor");

        this.requiredClass(HISTORY_PKG + ".SysmlCanonicalExtractor$CanonicalModelSnapshot");
        this.requiredClass(HISTORY_PKG + ".SysmlCanonicalExtractor$CanonicalElement");
        this.requiredClass(HISTORY_PKG + ".SysmlCanonicalExtractor$CanonicalRelationship");
    }

    // ========================================================================
    // 4. SysmlModelDiffService - diff() and inner ObjectDiff record
    // ========================================================================

    @DisplayName("SysmlModelDiffService exists with diff(CanonicalModelSnapshot,CanonicalModelSnapshot) and inner record ObjectDiff")
    @Test
    public void sysmlModelDiffServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".SysmlModelDiffService");

        this.requiredClass(HISTORY_PKG + ".SysmlModelDiffService$ObjectDiff");

        // diff method takes two CanonicalModelSnapshot parameters
        Class<?> snapshotClass = this.requiredClass(HISTORY_PKG + ".SysmlCanonicalExtractor$CanonicalModelSnapshot");
        try {
            Method diffMethod = service.getDeclaredMethod("diff", snapshotClass, snapshotClass);
            assertThat(diffMethod.getReturnType()).isEqualTo(java.util.List.class);
        } catch (NoSuchMethodException e) {
            fail("Expected SysmlModelDiffService to declare diff(CanonicalModelSnapshot,CanonicalModelSnapshot) returning List");
        }
    }

    // ========================================================================
    // 5. HeadMaterializationService
    // ========================================================================

    @DisplayName("HeadMaterializationService exists with materializeHead(String,UUID,UUID,CanonicalModelSnapshot,List)")
    @Test
    public void headMaterializationServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".HeadMaterializationService");
        Class<?> snapshotClass = this.requiredClass(HISTORY_PKG + ".SysmlCanonicalExtractor$CanonicalModelSnapshot");

        try {
            Method method = service.getDeclaredMethod("materializeHead", String.class, java.util.UUID.class,
                    java.util.UUID.class, snapshotClass, java.util.List.class);
            assertThat(method.getReturnType()).isEqualTo(void.class);
        } catch (NoSuchMethodException e) {
            fail("Expected HeadMaterializationService to declare materializeHead(String,UUID,UUID,CanonicalModelSnapshot,List)");
        }
    }

    // ========================================================================
    // 6. CommitPersistenceService
    // ========================================================================

    @DisplayName("CommitPersistenceService exists with persistCommit(UUID,UUID,UUID,String,List)")
    @Test
    public void commitPersistenceServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".CommitPersistenceService");

        try {
            Method method = service.getDeclaredMethod("persistCommit",
                    java.util.UUID.class, java.util.UUID.class, java.util.UUID.class,
                    String.class, java.util.List.class);
            assertThat(method.getReturnType()).isNotEqualTo(void.class);
        } catch (NoSuchMethodException e) {
            fail("Expected CommitPersistenceService to declare persistCommit(UUID,UUID,UUID,String,List)");
        }
    }

    // ========================================================================
    // 7. ModelSaveHistoryService
    // ========================================================================

    @DisplayName("ModelSaveHistoryService exists with processSave(IEditingContext,String,UUID,UUID)")
    @Test
    public void modelSaveHistoryServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".ModelSaveHistoryService");
        Class<?> editingContext = this.requiredClass("org.eclipse.sirius.components.core.api.IEditingContext");

        try {
            Method method = service.getDeclaredMethod("processSave",
                    editingContext, String.class, java.util.UUID.class, java.util.UUID.class);
            assertThat(method.getReturnType()).isEqualTo(void.class);
        } catch (NoSuchMethodException e) {
            fail("Expected ModelSaveHistoryService to declare processSave(IEditingContext,String,UUID,UUID)");
        }
    }

    // ========================================================================
    // 8. ModelReconstructionService
    // ========================================================================

    @DisplayName("ModelReconstructionService exists with reconstructCanonical(String,UUID)")
    @Test
    public void modelReconstructionServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".ModelReconstructionService");

        this.assertHasMethod(service, "reconstructCanonical", STRING, UUID);
    }

    // ========================================================================
    // 9. ElementHistoryService
    // ========================================================================

    @DisplayName("ElementHistoryService exists with getElementHistory(String,String,UUID)")
    @Test
    public void elementHistoryServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".ElementHistoryService");

        this.assertHasMethod(service, "getElementHistory", STRING, STRING, UUID);
    }

    // ========================================================================
    // 10. VersionGraphService - inner records
    // ========================================================================

    @DisplayName("VersionGraphService exists with inner records VersionGraphData and TagDto")
    @Test
    public void versionGraphServiceContract() {
        Class<?> service = this.requiredClass(HISTORY_PKG + ".VersionGraphService");

        this.requiredClass(HISTORY_PKG + ".VersionGraphService$VersionGraphData");
        this.requiredClass(HISTORY_PKG + ".VersionGraphService$TagDto");
    }

    // ========================================================================
    // 11. HeadElement entity
    // ========================================================================

    @DisplayName("HeadElement entity exists with @Entity and @Table(name='syson_head_elements')")
    @Test
    public void headElementEntityContract() {
        Class<?> entity = this.requiredClass(ENTITY_PKG + ".HeadElement");

        assertEntityAndTable(entity, "syson_head_elements");
    }

    // ========================================================================
    // 12. HeadRelationship entity
    // ========================================================================

    @DisplayName("HeadRelationship entity exists with @Entity and @Table(name='syson_head_relationships')")
    @Test
    public void headRelationshipEntityContract() {
        Class<?> entity = this.requiredClass(ENTITY_PKG + ".HeadRelationship");

        assertEntityAndTable(entity, "syson_head_relationships");
    }

    // ========================================================================
    // 13. HeadDiagram entity
    // ========================================================================

    @DisplayName("HeadDiagram entity exists with @Entity and @Table(name='syson_head_diagrams')")
    @Test
    public void headDiagramEntityContract() {
        Class<?> entity = this.requiredClass(ENTITY_PKG + ".HeadDiagram");

        assertEntityAndTable(entity, "syson_head_diagrams");
    }

    // ========================================================================
    // 14. BranchHead entity
    // ========================================================================

    @DisplayName("BranchHead entity exists with @Entity and @Table(name='syson_branch_heads')")
    @Test
    public void branchHeadEntityContract() {
        Class<?> entity = this.requiredClass(ENTITY_PKG + ".BranchHead");

        assertEntityAndTable(entity, "syson_branch_heads");
    }

    // ========================================================================
    // 15. BranchLock entity
    // ========================================================================

    @DisplayName("BranchLock entity exists with @Entity and @Table(name='syson_branch_locks')")
    @Test
    public void branchLockEntityContract() {
        Class<?> entity = this.requiredClass(LOCKS_ENTITY_PKG + ".BranchLock");

        assertEntityAndTable(entity, "syson_branch_locks");
    }

    // ========================================================================
    // 16. ElementLock entity
    // ========================================================================

    @DisplayName("ElementLock entity exists with @Entity and @Table(name='syson_element_locks')")
    @Test
    public void elementLockEntityContract() {
        Class<?> entity = this.requiredClass(LOCKS_ENTITY_PKG + ".ElementLock");

        assertEntityAndTable(entity, "syson_element_locks");
    }

    // ========================================================================
    // 17. Tag entity
    // ========================================================================

    @DisplayName("Tag entity exists with @Entity and @Table(name='syson_tags')")
    @Test
    public void tagEntityContract() {
        Class<?> entity = this.requiredClass(LOCKS_ENTITY_PKG + ".Tag");

        assertEntityAndTable(entity, "syson_tags");
    }

    // ========================================================================
    // 18. MergeRequest entity
    // ========================================================================

    @DisplayName("MergeRequest entity exists with @Entity and @Table(name='syson_merge_requests')")
    @Test
    public void mergeRequestEntityContract() {
        Class<?> entity = this.requiredClass(LOCKS_ENTITY_PKG + ".MergeRequest");

        assertEntityAndTable(entity, "syson_merge_requests");
    }

    // ========================================================================
    // 19. MergeConflict entity
    // ========================================================================

    @DisplayName("MergeConflict entity exists with @Entity and @Table(name='syson_merge_conflicts')")
    @Test
    public void mergeConflictEntityContract() {
        Class<?> entity = this.requiredClass(LOCKS_ENTITY_PKG + ".MergeConflict");

        assertEntityAndTable(entity, "syson_merge_conflicts");
    }

    // ========================================================================
    // 20. IntegrityCheck entity
    // ========================================================================

    @DisplayName("IntegrityCheck entity exists with @Entity and @Table(name='syson_integrity_checks')")
    @Test
    public void integrityCheckEntityContract() {
        Class<?> entity = this.requiredClass(LOCKS_ENTITY_PKG + ".IntegrityCheck");

        assertEntityAndTable(entity, "syson_integrity_checks");
    }

    // ========================================================================
    // 21. All repositories exist
    // ========================================================================

    @DisplayName("All history and locks repositories exist")
    @Test
    public void allRepositoriesExist() {
        // History repositories
        this.requiredClass(REPO_PKG + ".HeadElementRepository");
        this.requiredClass(REPO_PKG + ".HeadRelationshipRepository");
        this.requiredClass(REPO_PKG + ".HeadDiagramRepository");
        this.requiredClass(REPO_PKG + ".HeadPresentationElementRepository");
        this.requiredClass(REPO_PKG + ".BranchHeadRepository");
        this.requiredClass(REPO_PKG + ".ModelSnapshotRepository");
        this.requiredClass(REPO_PKG + ".CommitParentRepository");

        // Locks repositories
        this.requiredClass(LOCKS_REPO_PKG + ".BranchLockRepository");
        this.requiredClass(LOCKS_REPO_PKG + ".ElementLockRepository");
        this.requiredClass(LOCKS_REPO_PKG + ".TagRepository");
        this.requiredClass(LOCKS_REPO_PKG + ".MergeRequestRepository");
        this.requiredClass(LOCKS_REPO_PKG + ".MergeConflictRepository");
        this.requiredClass(LOCKS_REPO_PKG + ".IntegrityCheckRepository");
    }

    // ========================================================================
    // 22. BranchLockService
    // ========================================================================

    @DisplayName("BranchLockService exists with acquireLock, releaseLock, refreshLock, getLock, forceRelease")
    @Test
    public void branchLockServiceContract() {
        Class<?> service = this.requiredClass(LOCKS_SERVICE_PKG + ".BranchLockService");

        this.assertHasMethod(service, "acquireLock", STRING, UUID, UUID, STRING, STRING, STRING, "int");
        this.assertHasMethod(service, "releaseLock", STRING, UUID, STRING, UUID);
        this.assertHasMethod(service, "refreshLock", STRING, UUID, STRING, UUID, "int");
        this.assertHasMethod(service, "getLock", STRING, UUID, STRING);
        this.assertHasMethod(service, "forceRelease", STRING, UUID, STRING, UUID);
    }

    // ========================================================================
    // 23. ElementLockService
    // ========================================================================

    @DisplayName("ElementLockService exists with acquireLock, releaseLock, refreshLock, getLock, forceRelease")
    @Test
    public void elementLockServiceContract() {
        Class<?> service = this.requiredClass(LOCKS_SERVICE_PKG + ".ElementLockService");

        this.assertHasMethod(service, "acquireLock", STRING, UUID, STRING, UUID, STRING, STRING, STRING, STRING, "int");
        this.assertHasMethod(service, "releaseLock", STRING, UUID, STRING, UUID);
        this.assertHasMethod(service, "refreshLock", STRING, UUID, STRING, UUID, "int");
        this.assertHasMethod(service, "getLock", STRING, UUID, STRING);
        this.assertHasMethod(service, "forceRelease", STRING, UUID, STRING, UUID);
    }

    // ========================================================================
    // 24. IntegrityCheckService
    // ========================================================================

    @DisplayName("IntegrityCheckService exists with runCheck(String,UUID,UUID)")
    @Test
    public void integrityCheckServiceContract() {
        Class<?> service = this.requiredClass(LOCKS_SERVICE_PKG + ".IntegrityCheckService");

        this.assertHasMethod(service, "runCheck", STRING, UUID, UUID);
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Class<?> requiredClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            return fail("Missing enterprise history/warehouse contract class: " + className);
        }
    }

    private void assertHasMethod(Class<?> type, String methodName, String... parameterSimpleNames) {
        Set<String> candidates = Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(this::signature)
                .collect(Collectors.toSet());

        String expected = methodName + "(" + String.join(",", parameterSimpleNames) + ")";
        assertThat(candidates)
                .as("Expected %s to expose %s; available overloads: %s", type.getName(), expected, candidates)
                .contains(expected);
    }

    private String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + ")";
    }

    /**
     * Asserts that the given class has both {@code @Entity} and
     * {@code @Table(name = "expectedTableName")} annotations.
     */
    private void assertEntityAndTable(Class<?> entity, String expectedTableName) {
        // Check @Entity
        boolean hasEntity = false;
        for (Annotation annotation : entity.getAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals("Entity")) {
                hasEntity = true;
                break;
            }
        }
        assertThat(hasEntity)
                .as("Expected %s to be annotated with @Entity", entity.getName())
                .isTrue();

        // Check @Table with name
        boolean hasCorrectTable = false;
        for (Annotation annotation : entity.getAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals("Table")) {
                try {
                    Method nameMethod = annotation.annotationType().getMethod("name");
                    Object tableName = nameMethod.invoke(annotation);
                    if (expectedTableName.equals(tableName)) {
                        hasCorrectTable = true;
                    }
                } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                    // Annotation present but name() not readable
                }
                break;
            }
        }
        assertThat(hasCorrectTable)
                .as("Expected %s to have @Table(name = \"%s\")", entity.getName(), expectedTableName)
                .isTrue();
    }
}
