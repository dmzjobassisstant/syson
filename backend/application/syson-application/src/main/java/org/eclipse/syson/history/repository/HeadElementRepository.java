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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.history.entity.HeadElement;
import org.eclipse.syson.history.entity.HeadElementId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link HeadElement}.
 *
 * @author syson-team
 */
@Repository
public interface HeadElementRepository extends JpaRepository<HeadElement, HeadElementId> {

    List<HeadElement> findByProjectIdAndBranchIdAndDeletedFalse(String projectId, UUID branchId);

    Optional<HeadElement> findByProjectIdAndBranchIdAndStableId(String projectId, UUID branchId, String stableId);

    void deleteByProjectIdAndBranchId(String projectId, UUID branchId);

    /**
     * Finds all descendants of an element using recursive CTE on owner_stable_id.
     * Returns stable_ids of all children, grandchildren, etc. (not including the root).
     */
    @Query(value = """
        WITH RECURSIVE descendants AS (
            SELECT stable_id, owner_stable_id, name, sysml_type
            FROM syson_head_elements
            WHERE project_id = :projectId
              AND branch_id = CAST(:branchId AS uuid)
              AND owner_stable_id = :parentStableId
              AND NOT is_deleted
            UNION ALL
            SELECT e.stable_id, e.owner_stable_id, e.name, e.sysml_type
            FROM syson_head_elements e
            INNER JOIN descendants d ON e.owner_stable_id = d.stable_id
            WHERE e.project_id = :projectId
              AND e.branch_id = CAST(:branchId AS uuid)
              AND NOT e.is_deleted
        )
        SELECT stable_id FROM descendants
        """, nativeQuery = true)
    List<String> findDescendantStableIds(
            @Param("projectId") String projectId,
            @Param("branchId") String branchId,
            @Param("parentStableId") String parentStableId);
}
