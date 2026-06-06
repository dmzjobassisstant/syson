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
package org.eclipse.syson.vc.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for {@code syson_commits} API payloads.
 *
 * @author syson-team
 */
public record CommitDto(
        UUID commitId,
        UUID projectId,
        UUID branchId,
        long commitNumber,
        String message,
        UUID authorUserId,
        int changeCount,
        String commitHash,
        String parentCommitIds,
        OffsetDateTime committedAt,
        String source,
        String status) {
}
