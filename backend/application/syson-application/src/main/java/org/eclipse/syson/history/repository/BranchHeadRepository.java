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
package org.eclipse.syson.history.repository;

import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.history.entity.BranchHead;
import org.eclipse.syson.history.entity.BranchHeadId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link BranchHead}.
 *
 * @author syson-team
 */
@Repository
public interface BranchHeadRepository extends JpaRepository<BranchHead, BranchHeadId> {

    Optional<BranchHead> findByProjectIdAndBranchId(String projectId, UUID branchId);

    @Query("SELECT bh.canonicalJson FROM BranchHead bh WHERE bh.projectId = :projectId AND bh.branchId = :branchId")
    String getCanonicalJson(@Param("projectId") String projectId, @Param("branchId") UUID branchId);

    @Query("SELECT bh.canonicalHash FROM BranchHead bh WHERE bh.projectId = :projectId AND bh.branchId = :branchId")
    String getCanonicalHash(@Param("projectId") String projectId, @Param("branchId") UUID branchId);

    @Modifying
    @Query(value = "INSERT INTO syson_branch_heads (project_id, branch_id, head_commit_id, canonical_hash, object_count, relationship_count, diagram_count, last_extracted_at) "
            + "VALUES (:projectId, :branchId, :commitId, :canonicalHash, :created, :updated, :deleted, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (project_id, branch_id) DO UPDATE SET head_commit_id = EXCLUDED.head_commit_id, canonical_hash = EXCLUDED.canonical_hash, "
            + "object_count = EXCLUDED.object_count, relationship_count = EXCLUDED.relationship_count, diagram_count = EXCLUDED.diagram_count, last_extracted_at = EXCLUDED.last_extracted_at",
            nativeQuery = true)
    void upsertBranchHead(@Param("projectId") String projectId, @Param("branchId") UUID branchId,
                          @Param("commitId") UUID commitId, @Param("canonicalHash") String canonicalHash,
                          @Param("created") int created, @Param("updated") int updated, @Param("deleted") int deleted);

    @Modifying
    @Query(value = "INSERT INTO syson_branches (project_id, tenant_id, name, branch_type, is_protected, is_deleted, created_by, created_at, updated_at) "
            + "SELECT :projectId::uuid, :tenantId, 'main', 'main', false, false, :createdBy, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
            + "WHERE NOT EXISTS (SELECT 1 FROM syson_branches WHERE project_id = :projectId::uuid AND name = 'main' AND is_deleted = false)",
            nativeQuery = true)
    void ensureDefaultBranch(@Param("projectId") String projectId, @Param("tenantId") UUID tenantId, @Param("createdBy") UUID createdBy);
}
