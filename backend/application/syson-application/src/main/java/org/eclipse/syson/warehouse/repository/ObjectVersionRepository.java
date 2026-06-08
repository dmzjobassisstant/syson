package org.eclipse.syson.warehouse.repository;

import org.eclipse.syson.warehouse.entity.ObjectVersionEntity;
import org.eclipse.syson.warehouse.entity.ObjectVersionEntity.ObjectVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ObjectVersionRepository extends JpaRepository<ObjectVersionEntity, ObjectVersionId> {

    /**
     * All versions of an element, newest first. Used by element history API.
     */
    @Query("SELECT v FROM ObjectVersionEntity v WHERE v.id.projectId = :projectId AND v.id.stableObjectId = :stableId AND v.id.objectType = 'element' ORDER BY v.validFromCommitNumber DESC")
    List<ObjectVersionEntity> findElementHistory(@Param("projectId") String projectId, @Param("stableId") String stableId);

    /**
     * Current version of a specific object.
     */
    @Query("SELECT v FROM ObjectVersionEntity v WHERE v.id.projectId = :projectId AND v.id.stableObjectId = :stableId AND v.id.objectType = :objectType AND v.current = true")
    ObjectVersionEntity findCurrentVersion(@Param("projectId") String projectId, @Param("stableId") String stableId, @Param("objectType") String objectType);
}
