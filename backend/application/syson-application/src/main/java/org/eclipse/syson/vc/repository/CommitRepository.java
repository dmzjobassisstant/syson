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
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.vc.entity.CommitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link CommitEntity}.
 *
 * @author syson-team
 */
@Repository
public interface CommitRepository extends JpaRepository<CommitEntity, UUID> {

    List<CommitEntity> findByProjectIdAndBranchIdOrderByCommittedAtDesc(UUID projectId, UUID branchId);

    Optional<CommitEntity> findTopByProjectIdAndBranchIdOrderByCommitNumberDesc(UUID projectId, UUID branchId);

    Optional<CommitEntity> findByCommitIdAndProjectId(UUID commitId, UUID projectId);

    long countByProjectIdAndBranchId(UUID projectId, UUID branchId);
}
