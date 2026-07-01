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
 *     syson-team
 *******************************************************************************/
package org.eclipse.syson.sysml.dto;

import java.util.List;
import java.util.UUID;

import org.eclipse.sirius.components.core.api.IInput;

/**
 * The input object of the updateElement mutation.
 * <p>
 * Allows direct modification of an existing SysML element's properties
 * (name, short name, body/description) without requiring a collaborative
 * representation event processor. This is an IEditingContextEventHandler,
 * meaning it works at any time -- no WebSocket tree subscription needed.
 * </p>
 *
 * @author syson-team
 */
public record UpdateElementInput(UUID id, String editingContextId, String elementId,
        String newLabel, String newShortName, String newBody,
        List<KeyValueInput> properties) implements IInput {

    /**
     * Key-value pair for arbitrary string properties.
     */
    public record KeyValueInput(String key, String value) {
    }
}
