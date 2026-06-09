package org.eclipse.syson.settings;

import org.eclipse.syson.settings.ProjectSetting.ProjectSettingId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ProjectSettingService {

    private final ProjectSettingRepository repository;

    public ProjectSettingService(ProjectSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * Gets a single project setting value, returning the default if not set.
     */
    public String get(String projectId, String key, String defaultValue) {
        return repository.findByIdProjectIdAndIdKey(projectId, key)
                .map(ProjectSetting::getValue)
                .orElse(defaultValue);
    }

    /**
     * Returns true if the setting value is truthy (true, "true", "1", "yes").
     */
    public boolean isEnabled(String projectId, String key) {
        String val = get(projectId, key, "false");
        return "true".equals(val) || "\"true\"".equals(val) || "1".equals(val) || "\"yes\"".equals(val);
    }

    /**
     * Gets all settings for a project as a map.
     */
    public Map<String, String> getAll(String projectId) {
        List<ProjectSetting> settings = repository.findByIdProjectId(projectId);
        Map<String, String> result = new LinkedHashMap<>();
        for (ProjectSetting s : settings) {
            result.put(s.getId().getKey(), s.getValue());
        }
        return result;
    }

    /**
     * Sets a project setting value.
     */
    public void set(String projectId, String key, String value, String description, UUID userId) {
        ProjectSetting setting = repository.findByIdProjectIdAndIdKey(projectId, key)
                .orElse(new ProjectSetting(projectId, key, value, description));
        setting.setValue(value);
        setting.setUpdatedBy(userId);
        setting.setUpdatedAt(OffsetDateTime.now());
        if (description != null) {
            setting.setDescription(description);
        }
        repository.save(setting);
    }
}
