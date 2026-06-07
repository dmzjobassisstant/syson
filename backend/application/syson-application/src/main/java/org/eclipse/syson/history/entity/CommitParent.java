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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA entity mapped to the {@code syson_commit_parents} table. Stores the ordered parent chain for a commit,
 * supporting both linear and merge histories.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_commit_parents")
@IdClass(CommitParentId.class)
public class CommitParent {

    @Id
    @Column(name = "commit_id", nullable = false)
    private UUID commitId;

    @Id
    @Column(name = "parent_commit_id", nullable = false)
    private UUID parentCommitId;

    @Id
    @Column(name = "parent_order", nullable = false)
    private int parentOrder;

    public CommitParent() {
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
}
