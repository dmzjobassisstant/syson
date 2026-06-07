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
 * Composite ID for {@link CommitParent}.
 *
 * @author syson-team
 */
public class CommitParentId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID commitId;

    private UUID parentCommitId;

    private int parentOrder;

    public CommitParentId() {
    }

    public CommitParentId(UUID commitId, UUID parentCommitId, int parentOrder) {
        this.commitId = commitId;
        this.parentCommitId = parentCommitId;
        this.parentOrder = parentOrder;
    }

    public UUID getCommitId() {
        return this.commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public UUID getParentCommitId() {
        return this.parentCommitId;
    }

    public void setParentCommitId(UUID parentCommitId) {
        this.parentCommitId = parentCommitId;
    }

    public int getParentOrder() {
        return this.parentOrder;
    }

    public void setParentOrder(int parentOrder) {
        this.parentOrder = parentOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }
        CommitParentId that = (CommitParentId) o;
        return this.parentOrder == that.parentOrder
                && Objects.equals(this.commitId, that.commitId)
                && Objects.equals(this.parentCommitId, that.parentCommitId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.commitId, this.parentCommitId, this.parentOrder);
    }
}
