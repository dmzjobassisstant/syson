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
package org.eclipse.syson.chat.dto;

import java.util.Map;
import java.util.UUID;

/**
 * A single change operation for modifying a SysML model.
 *
 * @author syson-team
 */
public record ChangeOperation(String operation, UUID parentId, String elementType, String name, UUID targetId,
        Map<String, Object> properties, UUID sourceId, UUID targetId_, String relationshipType) {
}
