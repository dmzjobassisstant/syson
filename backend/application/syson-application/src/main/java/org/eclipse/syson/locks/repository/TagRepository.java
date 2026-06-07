package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findByProjectIdOrderByName(String projectId);

    Optional<Tag> findByProjectIdAndName(String projectId, String name);

    List<Tag> findByCommitId(UUID commitId);

    long countByProjectId(String projectId);
}
