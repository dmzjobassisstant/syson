package org.eclipse.syson.history.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.locks.entity.Tag;
import org.eclipse.syson.locks.repository.TagRepository;
import org.eclipse.syson.vc.dto.BaselineDto;
import org.eclipse.syson.vc.dto.BranchDto;
import org.eclipse.syson.vc.dto.CommitDto;
import org.eclipse.syson.vc.repository.BaselineRepository;
import org.eclipse.syson.vc.repository.BranchRepository;
import org.eclipse.syson.vc.repository.CommitRepository;
import org.springframework.stereotype.Service;

/**
 * Provides version graph data for the GitGraph UI visualization.
 * <p>
 * Fetches all branches, commits, baselines, and tags for a project,
 * and assigns lane indices to branches for visualization.
 * </p>
 *
 * @author syson-team
 */
@Service
public class VersionGraphService {

    private final BranchRepository branchRepository;
    private final CommitRepository commitRepository;
    private final BaselineRepository baselineRepository;
    private final TagRepository tagRepository;

    public VersionGraphService(BranchRepository branchRepository,
                                CommitRepository commitRepository,
                                BaselineRepository baselineRepository,
                                TagRepository tagRepository) {
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.baselineRepository = baselineRepository;
        this.tagRepository = tagRepository;
    }

    /**
     * Record containing all version graph data for the GitGraph UI.
     */
    public record VersionGraphData(
            List<BranchDto> branches,
            List<CommitDto> commits,
            List<BaselineDto> baselines,
            List<TagDto> tags
    ) {}

    /**
     * Record representing a tag in the version graph.
     */
    public record TagDto(
            UUID tagId,
            String projectId,
            UUID branchId,
            UUID commitId,
            String name,
            String description,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {}

    /**
     * Gets the complete version graph for a project.
     *
     * @param projectId the project UUID
     * @return version graph data
     */
    public VersionGraphData getVersionGraph(UUID projectId) {
        // Fetch all non-deleted branches (uses new method on BranchRepository)
        var branchEntities = branchRepository.findByProjectIdAndIsDeletedFalse(projectId);
        List<BranchDto> branches = branchEntities.stream()
                .map(b -> new BranchDto(
                        b.getBranchId(), b.getProjectId(), b.getTenantId(),
                        b.getName(), b.getBranchType(),
                        b.getHeadCommitId(), b.getBaseCommitId(), b.getParentBranchId(),
                        b.isProtected(), b.isDeleted(),
                        b.getCreatedAt(), b.getUpdatedAt(), b.getCreatedBy()))
                .toList();

        // Fetch all commits across all branches for this project
        List<CommitDto> commits = new ArrayList<>();
        for (var branch : branchEntities) {
            commits.addAll(commitRepository.findByProjectIdAndBranchIdOrderByCommittedAtDesc(projectId, branch.getBranchId())
                    .stream()
                    .map(e -> new CommitDto(
                            e.getCommitId(), e.getProjectId(), e.getBranchId(),
                            e.getCommitNumber(), e.getMessage(), e.getAuthorUserId(),
                            e.getChangeCount(), e.getCommitHash(), e.getParentCommitIds(),
                            e.getCommittedAt(), e.getSource(), e.getStatus()))
                    .toList());
        }

        // Fetch all baselines
        List<BaselineDto> baselines = baselineRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(e -> new BaselineDto(
                        e.getBaselineId(), e.getProjectId(), e.getTenantId(),
                        e.getBaselineCode(), e.getName(), e.getCommitId(),
                        e.getStatus(), e.getApprovedBy(), e.getApprovedAt(),
                        e.getDescription(), e.getCreatedAt(), e.getCreatedBy()))
                .toList();

        // Fetch all tags (Tag uses String projectId, so convert)
        List<TagDto> tags = tagRepository.findByProjectIdOrderByName(projectId.toString())
                .stream()
                .map(t -> new TagDto(
                        t.getTagId(), t.getProjectId(), t.getBranchId(),
                        t.getCommitId(), t.getName(), t.getDescription(),
                        t.getCreatedBy(), t.getCreatedAt()))
                .toList();

        return new VersionGraphData(branches, commits, baselines, tags);
    }
}
