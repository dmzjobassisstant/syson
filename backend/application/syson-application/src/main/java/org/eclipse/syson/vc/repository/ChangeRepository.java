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
package org.eclipse.syson.vc.repository;

import java.util.List;
import java.util.UUID;

import org.eclipse.syson.vc.entity.ChangeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ChangeEntity}.
 *
 * @author syson-team
 */
@Repository
public interface ChangeRepository extends JpaRepository<ChangeEntity, UUID> {

    List<ChangeEntity> findByCommitIdOrderByChangeSeq(UUID commitId);

    List<ChangeEntity> findByProjectIdAndObjectTypeAndObjectIdOrderByCreatedAtDesc(
            UUID projectId, String objectType, UUID objectId);

    long countByCommitId(UUID commitId);

    long countByProjectId(UUID projectId);

    @Query(value = "SELECT c.commit_id, c.operation, "
            + "COALESCE(b.name, 'unknown') AS branch_name, "
            + "COALESCE(u.email, CAST(c.created_by AS text)) AS author, "
            + "COALESCE(cm.message, '') AS message, "
            + "c.created_at AS committed_at, "
            + "COALESCE(c.changed_fields, '[]') AS changed_fields, "
            + "COALESCE(c.patch, '{}') AS patch "
            + "FROM syson_changes c "
            + "LEFT JOIN syson_commits cm ON c.commit_id = cm.commit_id "
            + "LEFT JOIN syson_branches b ON c.branch_id = b.branch_id "
            + "LEFT JOIN syson_users u ON c.created_by = u.id "
            + "WHERE c.object_id = ?1 AND c.project_id = ?2 "
            + "ORDER BY c.created_at DESC",
            nativeQuery = true)
    List<Object[]> findByObjectIdAndProjectId(@Param("objectId") UUID objectId, @Param("projectId") UUID projectId);
}
