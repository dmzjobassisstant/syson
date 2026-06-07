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
package org.eclipse.syson.history.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite ID for {@link BranchHead}.
 *
 * @author syson-team
 */
public class BranchHeadId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String projectId;

    private UUID branchId;

    public BranchHeadId() {
    }

    public BranchHeadId(String projectId, UUID branchId) {
        this.projectId = projectId;
        this.branchId = branchId;
    }

    public String getProjectId() {
        return this.projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public UUID getBranchId() {
        return this.branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }
        BranchHeadId that = (BranchHeadId) o;
        return Objects.equals(this.projectId, that.projectId)
                && Objects.equals(this.branchId, that.branchId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.projectId, this.branchId);
    }
}
