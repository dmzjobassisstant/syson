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

/**
 * Response from SysML validation.
 *
 * @author syson-team
 */
public record ValidateResponse(boolean valid, List<Diagnostic> errors, int errorCount, int warningCount) {
}
