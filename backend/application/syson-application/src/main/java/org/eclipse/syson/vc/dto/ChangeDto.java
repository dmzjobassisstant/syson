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
 * DTO for {@code syson_changes} API payloads.
 *
 * @author syson-team
 */
public record ChangeDto(
        UUID changeId,
        UUID projectId,
        UUID commitId,
        int changeSeq,
        String objectType,
        UUID objectId,
        String operation,
        String beforeHash,
        String afterHash,
        String patch,
        String beforeObject,
        String afterObject,
        OffsetDateTime createdAt,
        UUID createdBy,
        String stableObjectId,
        String changedFieldsJson) {
}
