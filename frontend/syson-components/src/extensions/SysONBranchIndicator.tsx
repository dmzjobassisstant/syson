/*******************************************************************************
 * Copyright (c) 2026 SysMLv2 Architect.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     SysMLv2 Architect - branch indicator for editor toolbar
 *******************************************************************************/

import { DiagramPanelActionProps } from '@eclipse-sirius/sirius-components-diagrams';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import { useEffect, useState } from 'react';

/**
 * Displays the current branch name in the diagram editor toolbar.
 *
 * Loads the branch from:
 *   1. localStorage (set by the dashboard VC panel when selecting a branch)
 *   2. The API default-branch endpoint (fallback)
 *
 * Updates every 10 seconds and on window focus to reflect
 * branch switches made in other tabs/windows.
 */
export const SysONBranchIndicator = ({ editingContextId }: DiagramPanelActionProps) => {
  const [branchName, setBranchName] = useState<string>('');
  const [loading, setLoading] = useState(true);

  const resolveBranch = async () => {
    const projectId = editingContextId;
    const token = (() => {
      try {
        const raw = localStorage.getItem('syson_auth');
        return raw ? JSON.parse(raw).token : null;
      } catch (_) {
        return null;
      }
    })();

    try {
      // Check localStorage first
      const localBranchId = (() => {
        try {
          return localStorage.getItem('syson-vc-branch-' + projectId);
        } catch (_) {
          return null;
        }
      })();

      const baseUrl = window.location.origin;
      let branchId = localBranchId;

      if (!branchId) {
        // Fallback: fetch default branch from API
        const res = await fetch(
          baseUrl + '/api/v1/projects/' + projectId + '/settings/default-branch',
          { headers: { Authorization: 'Bearer ' + (token || '') } }
        );
        const data = await res.json();
        branchId = data.branchId || null;
      }

      if (branchId) {
        // Fetch branch name from the branches endpoint
        try {
          // Use the version-control tree endpoint to get branch names
          const treeRes = await fetch(
            baseUrl + '/api/v1/projects/' + projectId + '/version-control/tree',
            { headers: { Authorization: 'Bearer ' + (token || '') } }
          );
          const tree = await treeRes.json();
          const branches = tree.branches || [];
          const branch = branches.find((b: any) => b.branchId === branchId);
          setBranchName(branch ? (branch.name || branchId.substring(0, 8)) : branchId.substring(0, 8));
        } catch (_) {
          setBranchName(branchId.substring(0, 8));
        }
      } else {
        setBranchName('main');
      }
    } catch (_) {
      setBranchName('main');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    resolveBranch();
    // Refresh periodically and on window focus
    const interval = setInterval(resolveBranch, 10000);
    const onFocus = () => resolveBranch();
    window.addEventListener('focus', onFocus);
    return () => {
      clearInterval(interval);
      window.removeEventListener('focus', onFocus);
    };
  }, [editingContextId]);

  if (loading) return null;

  return (
    <Tooltip title={'Working branch: ' + branchName} placement="bottom">
      <Chip
        icon={<AccountTreeIcon fontSize="small" />}
        label={branchName}
        size="small"
        variant="outlined"
        sx={{
          fontSize: '0.7rem',
          height: 24,
          borderColor: '#3b82f6',
          color: '#93c5fd',
          backgroundColor: 'rgba(59,130,246,0.1)',
          '& .MuiChip-icon': { color: '#3b82f6', marginLeft: '4px' },
          fontWeight: 600,
        }}
      />
    </Tooltip>
  );
};
