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

import java.util.List;
import java.util.UUID;

/**
 * Request to execute approved changes on a SysML model.
 *
 * @author syson-team
 */
public record ChatExecuteRequest(UUID conversationId, UUID branchId, List<ChangeOperation> changes) {
}
