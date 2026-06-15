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

import java.util.List;
import java.util.UUID;

import org.eclipse.syson.chat.dto.ChangeOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes approved change operations on a SysML model.
 * <p>
 * Currently a placeholder; will be wired to Sirius GraphQL mutations
 * in a future iteration.
 *
 * @author syson-team
 */
@Service
public class ChangeExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(ChangeExecutionService.class);

    /**
     * Executes a list of change operations on the specified project/branch.
     *
     * @param projectId the project ID
     * @param branchId  the branch ID
     * @param changes   the list of change operations to execute
     * @return a summary string describing the result
     */
    public String executeChanges(UUID projectId, UUID branchId, List<ChangeOperation> changes) {
        if (changes == null || changes.isEmpty()) {
            LOG.info("No changes to execute for project {} branch {}", projectId, branchId);
            return "No changes to execute.";
        }

        LOG.info("Executing {} changes for project {} branch {}", changes.size(), projectId, branchId);

        int created = 0;
        int updated = 0;
        int deleted = 0;

        for (ChangeOperation change : changes) {
            if ("CREATE".equalsIgnoreCase(change.operation())) {
                created++;
                LOG.debug("CREATE {} named '{}'", change.elementType(), change.name());
            } else if ("UPDATE".equalsIgnoreCase(change.operation())) {
                updated++;
                LOG.debug("UPDATE {} (targetId={})", change.elementType(), change.targetId());
            } else if ("DELETE".equalsIgnoreCase(change.operation())) {
                deleted++;
                LOG.debug("DELETE {} (targetId={})", change.elementType(), change.targetId());
            } else {
                LOG.warn("Unknown operation: {}", change.operation());
            }
        }

        // Placeholder: in production this will call Sirius GraphQL mutations
        return String.format("Executed %d changes: %d created, %d updated, %d deleted.",
                changes.size(), created, updated, deleted);
    }

    /**
     * Validates that a list of changes can be applied without conflicts.
     *
     * @param projectId the project ID
     * @param changes   the list of changes to validate
     * @return true if the changes are valid
     */
    public boolean validateChanges(UUID projectId, List<ChangeOperation> changes) {
        if (changes == null || changes.isEmpty()) {
            return true;
        }
        for (ChangeOperation change : changes) {
            if (change.operation() == null || change.operation().isBlank()) {
                return false;
            }
            if ("CREATE".equalsIgnoreCase(change.operation()) && (change.name() == null || change.name().isBlank())) {
                return false;
            }
        }
        return true;
    }
}
