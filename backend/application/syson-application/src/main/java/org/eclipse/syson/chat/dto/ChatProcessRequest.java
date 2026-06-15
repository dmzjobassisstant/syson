/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.chat.dto;

import java.util.UUID;

/**
 * Unified chat request where the LLM decides the action type from the system prompt contract.
 */
public record ChatProcessRequest(String prompt, UUID conversationId, UUID branchId,
        String apiEndpoint, String apiKey, String model) {
}
