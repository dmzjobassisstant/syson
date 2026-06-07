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
 * Composite ID for {@link HeadDiagram}.
 *
 * @author syson-team
 */
public class HeadDiagramId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String projectId;

    private UUID branchId;

    private String diagramId;

    public HeadDiagramId() {
    }

    public HeadDiagramId(String projectId, UUID branchId, String diagramId) {
        this.projectId = projectId;
        this.branchId = branchId;
        this.diagramId = diagramId;
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

    public String getDiagramId() {
        return this.diagramId;
    }

    public void setDiagramId(String diagramId) {
        this.diagramId = diagramId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }
        HeadDiagramId that = (HeadDiagramId) o;
        return Objects.equals(this.projectId, that.projectId)
                && Objects.equals(this.branchId, that.branchId)
                && Objects.equals(this.diagramId, that.diagramId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.projectId, this.branchId, this.diagramId);
    }
}
