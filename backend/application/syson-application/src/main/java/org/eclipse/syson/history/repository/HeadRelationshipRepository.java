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

import org.eclipse.syson.history.entity.HeadRelationship;
import org.eclipse.syson.history.entity.HeadRelationshipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link HeadRelationship}.
 *
 * @author syson-team
 */
@Repository
public interface HeadRelationshipRepository extends JpaRepository<HeadRelationship, HeadRelationshipId> {

    List<HeadRelationship> findByProjectIdAndBranchIdAndIsDeletedFalse(String projectId, UUID branchId);

    List<HeadRelationship> findByProjectIdAndBranchIdAndSourceStableId(String projectId, UUID branchId, String sourceStableId);

    Optional<HeadRelationship> findByProjectIdAndBranchIdAndStableId(String projectId, UUID branchId, String stableId);
}
