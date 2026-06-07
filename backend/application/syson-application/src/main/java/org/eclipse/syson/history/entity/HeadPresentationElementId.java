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
 * Composite ID for {@link HeadPresentationElement}.
 *
 * @author syson-team
 */
public class HeadPresentationElementId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String projectId;

    private UUID branchId;

    private String presentationId;

    public HeadPresentationElementId() {
    }

    public HeadPresentationElementId(String projectId, UUID branchId, String presentationId) {
        this.projectId = projectId;
        this.branchId = branchId;
        this.presentationId = presentationId;
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

    public String getPresentationId() {
        return this.presentationId;
    }

    public void setPresentationId(String presentationId) {
        this.presentationId = presentationId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }
        HeadPresentationElementId that = (HeadPresentationElementId) o;
        return Objects.equals(this.projectId, that.projectId)
                && Objects.equals(this.branchId, that.branchId)
                && Objects.equals(this.presentationId, that.presentationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.projectId, this.branchId, this.presentationId);
    }
}
