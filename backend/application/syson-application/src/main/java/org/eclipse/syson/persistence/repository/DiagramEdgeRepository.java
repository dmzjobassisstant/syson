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

import org.eclipse.syson.persistence.entity.DiagramEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link DiagramEdgeEntity}.
 *
 * @author syson-team
 */
@Repository
public interface DiagramEdgeRepository extends JpaRepository<DiagramEdgeEntity, UUID> {

    List<DiagramEdgeEntity> findByDiagramIdAndIsDeletedFalse(UUID diagramId);
}
