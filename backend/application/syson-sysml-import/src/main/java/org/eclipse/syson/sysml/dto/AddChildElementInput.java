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

import java.util.UUID;

import org.eclipse.sirius.components.core.api.IInput;

/**
 * The input object of the addChildElement mutation.
 * <p>
 * Creates a new SysML element of the given type as a child of the specified
 * parent element. Uses the same EMF creation pattern as the diagram palette tools.
 * </p>
 *
 * @author syson-team
 */
public record AddChildElementInput(UUID id, String editingContextId, String parentElementId,
        String elementType, String name) implements IInput {
}
