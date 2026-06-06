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
}
