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
 * The input object of the deleteElement mutation.
 * <p>
 * Deletes an existing SysML element (and its containing Membership) directly,
 * without requiring a collaborative representation event processor.
 * </p>
 *
 * @author syson-team
 */
public record DeleteElementInput(UUID id, String editingContextId, String elementId) implements IInput {
}
