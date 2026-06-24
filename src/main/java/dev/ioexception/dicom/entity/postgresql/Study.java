package dev.ioexception.dicom.entity.postgresql;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hdcmstudy", schema = "public", uniqueConstraints = {
		@UniqueConstraint(name = "hdcmstudy$uk", columnNames = "study_instance_uid")
})
@Getter
@Setter
@NoArgsConstructor
public class Study {

	@Id
	@Column(name = "dcm_study_key", nullable = false)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hdcmstudy_seq_gen")
	@SequenceGenerator(
			name = "hdcmstudy_seq_gen",
			sequenceName = "hdcmstudy$sq",
			allocationSize = 1
	)
	private Integer dcmStudyKey;

	@Column(name = "study_instance_uid", nullable = false, length = 64)
	private String studyInstanceUid;

	@Column(name = "patient_key", nullable = false)
	private Integer patientKey;

	@Column(name = "patient_id_value", length = 64)
	private String patientIdValue;

	@Column(name = "patient_name", length = 256)
	private String patientName;

	@Column(name = "patient_sex", length = 1)
	private String patientSex;

	@Column(name = "patient_birth_dttm")
	private LocalDateTime patientBirthDttm;

	@Column(name = "study_id", length = 32)
	private String studyId;

	@Column(name = "study_dttm")
	private LocalDateTime studyDttm;

	@Column(name = "accession_no", length = 32)
	private String accessionNo;

	@Column(name = "study_desc", length = 64)
	private String studyDesc;

	@Column(name = "patient_age", length = 16)
	private String patientAge;

	@Column(name = "modality_list")
	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> modalityList = new ArrayList<>();

	@Column(name = "study_size")
	private Long studySize;

	@Column(name = "series_count")
	private Integer seriesCount;

	@Column(name = "instance_count")
	private Integer instanceCount;

	@Column(name = "created_dttm", nullable = false)
	private LocalDateTime createdDttm;

	@Column(name = "updated_dttm", nullable = false)
	private LocalDateTime updatedDttm;

	@Column(name = "deleted_dttm")
	private LocalDateTime deletedDttm;
}
