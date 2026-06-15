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
package org.eclipse.syson.chat;

import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Serializes the current project model to textual SysML v2 notation.
 * <p>
 * Currently returns placeholder text. Future iterations will use the
 * {@code syson-sysml-export} module for full serialization.
 *
 * @author syson-team
 */
@Service
public class ModelSerializationService {

    /**
     * Serializes the current state of a project model to SysML v2 textual notation.
     *
     * @param projectId the project ID
     * @param branchId  the branch ID (may be null for default branch)
     * @return the SysML v2 textual representation
     */
    public String serializeCurrentModel(UUID projectId, UUID branchId) {
        // Placeholder: in production, use syson-sysml-export to produce real SysML v2 text
        return "package ProjectModel {\n"
                + "    // Current model state for project: " + projectId + "\n"
                + "    // Branch: " + (branchId != null ? branchId : "default") + "\n"
                + "}\n";
    }
}
