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
import java.util.UUID;

import org.eclipse.syson.history.entity.HeadDiagram;
import org.eclipse.syson.history.entity.HeadDiagramId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link HeadDiagram}.
 *
 * @author syson-team
 */
@Repository
public interface HeadDiagramRepository extends JpaRepository<HeadDiagram, HeadDiagramId> {

    List<HeadDiagram> findByProjectIdAndBranchIdAndIsDeletedFalse(String projectId, UUID branchId);
}
