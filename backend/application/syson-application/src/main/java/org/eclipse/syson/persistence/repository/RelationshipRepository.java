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
package org.eclipse.syson.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.eclipse.syson.persistence.entity.RelationshipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link RelationshipEntity}.
 *
 * @author syson-team
 */
@Repository
public interface RelationshipRepository extends JpaRepository<RelationshipEntity, UUID> {

    List<RelationshipEntity> findByProjectIdAndBranchIdAndIsDeletedFalse(UUID projectId, UUID branchId);

    List<RelationshipEntity> findBySourceIdAndIsDeletedFalse(UUID sourceId);

    List<RelationshipEntity> findByTargetIdAndIsDeletedFalse(UUID targetId);

    List<RelationshipEntity> findByProjectIdAndBranchIdAndRelTypeAndIsDeletedFalse(UUID projectId, UUID branchId, String relType);
}
