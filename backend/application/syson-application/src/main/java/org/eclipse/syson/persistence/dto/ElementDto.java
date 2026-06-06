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
package org.eclipse.syson.persistence.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for {@code syson_elements} API payloads.
 *
 * @author syson-team
 */
public record ElementDto(
        UUID id,
        UUID projectId,
        UUID branchId,
        String sysmlType,
        String name,
        UUID ownerId,
        String body,
        boolean isAbstract,
        boolean isVariation,
        String attributes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
