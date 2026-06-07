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
}
