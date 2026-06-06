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
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.persistence.entity.ElementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ElementEntity}.
 *
 * @author syson-team
 */
@Repository
public interface ElementRepository extends JpaRepository<ElementEntity, UUID> {

    List<ElementEntity> findByProjectIdAndBranchIdAndIsDeletedFalse(UUID projectId, UUID branchId);

    Optional<ElementEntity> findByProjectIdAndBranchIdAndIdAndIsDeletedFalse(UUID projectId, UUID branchId, UUID id);

    List<ElementEntity> findByOwnerIdAndIsDeletedFalse(UUID ownerId);

    List<ElementEntity> findByProjectIdAndBranchIdAndSysmlTypeAndIsDeletedFalse(UUID projectId, UUID branchId, String sysmlType);

    long countByProjectIdAndBranchIdAndIsDeletedFalse(UUID projectId, UUID branchId);
}
