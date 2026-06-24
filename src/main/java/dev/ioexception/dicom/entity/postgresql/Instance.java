package dev.ioexception.dicom.entity.postgresql;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "hdcminstance", schema = "public", uniqueConstraints = {
		@UniqueConstraint(name = "hdcminstance$uk", columnNames = "sop_instance_uid")
})
@Getter
@Setter
@NoArgsConstructor
public class Instance {

	@Id
	@Column(name = "dcm_instance_key", nullable = false)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hdcminstance_seq_gen")
	@SequenceGenerator(
			name = "hdcminstance_seq_gen",
			sequenceName = "hdcminstance$sq",
			allocationSize = 1
	)
	private Long dcmInstanceKey;

	@Column(name = "sop_instance_uid", nullable = false, length = 64)
	private String sopInstanceUid;

	@Column(name = "sop_class_uid", nullable = false, length = 64)
	private String sopClassUid;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "dcm_series_key", nullable = false, foreignKey = @ForeignKey(name = "hdcminstance$fk_dcm_series_key"))
	private Series hdcmSeries;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "dcm_study_key", nullable = false, foreignKey = @ForeignKey(name = "hdcminstance$fk_dcm_study_key"))
	private Study hdcmStudy;

	@Column(name = "instance_no")
	private BigDecimal instanceNo;

	@Column(name = "content_dttm")
	private LocalDateTime contentDttm;

	@Column(name = "instance_size")
	private Long instanceSize;

	@Column(name = "location_root", length = 256)
	private String locationRoot;

	@Column(name = "location_path", length = 256)
	private String locationPath;

	@Column(name = "compressed", nullable = false)
	private Boolean compressed = false;

	@Column(name = "created_dttm", nullable = false)
	private LocalDateTime createdDttm;

	@Column(name = "updated_dttm", nullable = false)
	private LocalDateTime updatedDttm;

	@Column(name = "deleted_dttm")
	private LocalDateTime deletedDttm;
}
