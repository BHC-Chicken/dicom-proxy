package dev.ioexception.dicom.repository.postgresql;

import dev.ioexception.dicom.entity.postgresql.Study;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StudyRepository extends JpaRepository<Study, Integer> {

	/**
	 * HDcmStudyArchive_create 프로시저 호출
	 */
	@Query(value = "SELECT HDcmStudyArchive_create(:studyKey, :archiveName, :archiveSize)", nativeQuery = true)
	Long createStudyArchive(
			@Param("studyKey") Integer studyKey,
			@Param("archiveName") String archiveName,
			@Param("archiveSize") Long archiveSize);

	/**
	 * HDcmStudyArchive_remove_original 프로시저 호출 (SETOF 결과 반환)
	 */
	@Query(value = "SELECT * FROM HDcmStudyArchive_remove_original(:studyKey)", nativeQuery = true)
	List<Object[]> removeOriginalStudy(@Param("studyKey") Integer studyKey);

	/**
	 * 기간 내 생성된 Study 목록 조회
	 */
	List<Study> findByCreatedDttmGreaterThanEqualAndCreatedDttmLessThanOrderByCreatedDttm(LocalDateTime start, LocalDateTime end);
}
