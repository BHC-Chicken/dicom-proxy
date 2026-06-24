package dev.ioexception.dicom.entity.postgresql;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "hdcmseries", schema = "public", uniqueConstraints = {
		@UniqueConstraint(name = "hdcmseries$uk", columnNames = "series_instance_uid")
})
@Getter
@Setter
@NoArgsConstructor
public class Series {

	@Id
	@Column(name = "dcm_series_key", nullable = false)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hdcmseries_seq_gen")
	@SequenceGenerator(
			name = "hdcmseries_seq_gen",
			sequenceName = "hdcmseries$sq",
			allocationSize = 1
	)
	private Integer dcmSeriesKey;

	@Column(name = "series_instance_uid", nullable = false, length = 64)
	private String seriesInstanceUid;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "dcm_study_key", nullable = false, foreignKey = @ForeignKey(name = "hdcmseries$fk_dcm_study_key"))
	private Study hdcmStudy;

	@Column(name = "modality", nullable = false, length = 8)
	private String modality;

	@Column(name = "series_no")
	private BigDecimal seriesNo;

	@Column(name = "series_dttm")
	private LocalDateTime seriesDttm;

	@Column(name = "series_desc", length = 64)
	private String seriesDesc;

	@Column(name = "bodypart", length = 32)
	private String bodypart;

	@Column(name = "series_size")
	private Long seriesSize;

	@Column(name = "instance_count")
	private Integer instanceCount;

	@Column(name = "created_dttm", nullable = false)
	private LocalDateTime createdDttm;

	@Column(name = "deleted_dttm")
	private LocalDateTime deletedDttm;
}
