package dev.ioexception.dicom.repository.postgresql;

import dev.ioexception.dicom.entity.postgresql.Instance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstanceRepository extends JpaRepository<Instance, Long> {

	@Query("SELECT ins FROM Instance ins JOIN FETCH ins.hdcmSeries " +
			"WHERE ins.hdcmStudy.dcmStudyKey = :studyKey " +
			"ORDER BY ins.hdcmSeries.seriesNo, ins.instanceNo, ins.dcmInstanceKey")
	List<Instance> findInstancesForPurge(@Param("studyKey") Integer studyKey);
}
