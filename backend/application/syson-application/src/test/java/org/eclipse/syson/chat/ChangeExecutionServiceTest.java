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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.chat.dto.ChangeOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ChangeExecutionService} covering change execution and validation.
 *
 * @author syson-team
 */
class ChangeExecutionServiceTest {

    private ChangeExecutionService service;

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID BRANCH_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ChangeExecutionService();
    }

    @Test
    void executeChanges_withCreateOperation_shouldReturnSummary() {
        List<ChangeOperation> changes = List.of(
                new ChangeOperation("CREATE", PROJECT_ID, "part_def", "Engine", null,
                        Map.of("isAbstract", false), null, null, null));

        String result = service.executeChanges(PROJECT_ID, BRANCH_ID, changes);

        assertNotNull(result);
        assertTrue(result.contains("1 created"));
        assertTrue(result.contains("Executed 1 changes"));
    }

    @Test
    void executeChanges_withMultipleOperations_shouldCountCorrectly() {
        List<ChangeOperation> changes = List.of(
                new ChangeOperation("CREATE", null, "part_def", "A", null, null, null, null, null),
                new ChangeOperation("UPDATE", null, "attribute", null, UUID.randomUUID(), null, null, null, null),
                new ChangeOperation("DELETE", null, "port", null, UUID.randomUUID(), null, null, null, null));

        String result = service.executeChanges(PROJECT_ID, BRANCH_ID, changes);

        assertNotNull(result);
        assertTrue(result.contains("1 created"));
        assertTrue(result.contains("1 updated"));
        assertTrue(result.contains("1 deleted"));
        assertTrue(result.contains("Executed 3 changes"));
    }

    @Test
    void executeChanges_withEmptyList_shouldReturnNoChangesMessage() {
        String result = service.executeChanges(PROJECT_ID, BRANCH_ID, List.of());

        assertNotNull(result);
        assertTrue(result.contains("No changes to execute"));
    }

    @Test
    void executeChanges_withNullList_shouldReturnNoChangesMessage() {
        String result = service.executeChanges(PROJECT_ID, BRANCH_ID, null);

        assertNotNull(result);
        assertTrue(result.contains("No changes to execute"));
    }

    @Test
    void validateChanges_withValidChanges_shouldReturnTrue() {
        List<ChangeOperation> changes = List.of(
                new ChangeOperation("CREATE", PROJECT_ID, "part_def", "ValidPart", null,
                        null, null, null, null));

        boolean valid = service.validateChanges(PROJECT_ID, changes);

        assertTrue(valid);
    }

    @Test
    void validateChanges_withEmptyNameCreate_shouldReturnFalse() {
        List<ChangeOperation> changes = List.of(
                new ChangeOperation("CREATE", PROJECT_ID, "part_def", "", null,
                        null, null, null, null));

        boolean valid = service.validateChanges(PROJECT_ID, changes);

        assertFalse(valid);
    }

    @Test
    void validateChanges_withNullOperation_shouldReturnFalse() {
        List<ChangeOperation> changes = List.of(
                new ChangeOperation(null, PROJECT_ID, "part_def", "X", null,
                        null, null, null, null));

        boolean valid = service.validateChanges(PROJECT_ID, changes);

        assertFalse(valid);
    }
}
