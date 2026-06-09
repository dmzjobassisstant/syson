package org.eclipse.syson.settings;

import org.eclipse.syson.settings.ProjectSetting.ProjectSettingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectSettingRepository extends JpaRepository<ProjectSetting, ProjectSettingId> {

    List<ProjectSetting> findByIdProjectId(String projectId);

    Optional<ProjectSetting> findByIdProjectIdAndIdKey(String projectId, String key);
}
