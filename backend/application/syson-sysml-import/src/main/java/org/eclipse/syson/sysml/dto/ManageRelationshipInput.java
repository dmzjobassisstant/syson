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
 * The input object of the manageRelationship mutation.
 * <p>
 * Creates or removes a relationship between SysML elements. Supports
 * Dependency (client-supplier) and Subclassification/Specialization
 * (general-specific) relationships.
 * </p>
 *
 * @author syson-team
 */
public record ManageRelationshipInput(UUID id, String editingContextId,
        String relationshipType,
        String sourceElementId,
        List<String> targetElementIds,
        String action) implements IInput {

    /**
     * Action enum values.
     */
    public static final String ADD = "ADD";
    public static final String REMOVE = "REMOVE";
}
