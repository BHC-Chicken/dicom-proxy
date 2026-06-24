package dev.ioexception.dicom.service;

import dev.ioexception.dicom.entity.postgresql.Instance;
import dev.ioexception.dicom.entity.postgresql.Study;
import dev.ioexception.dicom.exception.dicom.DicomErrorCode;
import dev.ioexception.dicom.exception.dicom.DicomErrorException;
import dev.ioexception.dicom.repository.postgresql.StudyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DicomPurgeExecutor {

	private final StudyRepository studyRepository;

	private enum FileCheckStatus {
		SUCCESS, SIZE_MISMATCH, NOT_FOUND
	}

	/**
	 * [1단계 - Check] 개별 인스턴스 파일의 존재 여부 및 크기 일치 여부를 검증합니다.
	 * 검증 실패 시 즉시 예외를 던져 전체 프로세스를 롤백시킵니다.
	 */
	public void checkInstanceFiles(Integer studyKey, List<Instance> instances, String storageRootStr) {
		log.info("인스턴스 파일 정합성 검증 시작 (StudyKey: {}, 파일 수: {}개)", studyKey, instances.size());
		Path storageRoot = Paths.get(storageRootStr);
		int index = 0;

		for (Instance ins : instances) {
			index++;
			Path file = storageRoot.resolve(ins.getLocationRoot()).resolve(ins.getLocationPath());
			FileCheckStatus status = verifyInstanceFile(file, ins, index, instances.size());

			if (status == FileCheckStatus.NOT_FOUND) {
				throw new DicomErrorException(DicomErrorCode.INSTANCE_FILE_NOT_FOUND,
						String.format("파일을 찾을 수 없거나 읽을 수 없습니다. (InstanceKey: %d, Path: %s)", ins.getDcmInstanceKey(), file.toAbsolutePath()));
			}

			if (status == FileCheckStatus.SIZE_MISMATCH) {
				throw new DicomErrorException(DicomErrorCode.INSTANCE_SIZE_MISMATCH,
						String.format("파일 크기가 일치하지 않습니다. (InstanceKey: %d, Path: %s)", ins.getDcmInstanceKey(), file.toAbsolutePath()));
			}
		}
		log.info("인스턴스 파일 검증 완료 (StudyKey: {}, 검증 성공 수: {}개)", studyKey, instances.size());
	}

	/**
	 * 개별 인스턴스 파일 검증을 위한 헬퍼 메서드 (깊은 if-else Depth 해소용)
	 */
	private FileCheckStatus verifyInstanceFile(Path file, Instance ins, int index, int totalSize) {
		if (!Files.exists(file) || !Files.isReadable(file)) {
			log.error("> [{}/{}] 파일 없음/읽기 불가 - InstanceKey: {}, Path: {}",
					index, totalSize, ins.getDcmInstanceKey(), file.toAbsolutePath());

			return FileCheckStatus.NOT_FOUND;
		}

		try {
			long expectedSize = ins.getInstanceSize() != null ? ins.getInstanceSize() : 0;
			long actualSize = Files.size(file);
			if (actualSize == expectedSize) {
				return FileCheckStatus.SUCCESS;
			}
			log.warn("> [{}/{}] 크기 불일치 - InstanceKey: {}, Path: {}, DB크기: {}, 실제크기: {}",
					index, totalSize, ins.getDcmInstanceKey(), file.toAbsolutePath(), expectedSize, actualSize);

			return FileCheckStatus.SIZE_MISMATCH;
		} catch (IOException e) {
			log.error("> [{}/{}] 검증 중 파일 I/O 오류 발생 - InstanceKey: {}, Path: {}",
					index, totalSize, ins.getDcmInstanceKey(), file.toAbsolutePath(), e);

			return FileCheckStatus.NOT_FOUND;
		}
	}

	/**
	 * [2단계 - Archive] 대상 인스턴스들을 ZIP 파일로 압축 생성하고 DB 아카이브 이력에 등록합니다.
	 * DB 호출 및 트랜잭션 롤백, 임시 ZIP 파일 삭제 처리가 하나의 트랜잭션으로 제어됩니다.
	 */
	@Transactional("postgresTransactionManager")
	public boolean archiveStudyFiles(Study study, List<Instance> instances, String storageRootStr, String outputDirStr) {
		String zipFilename = String.format("STD_%s.zip", study.getStudyInstanceUid());
		Path outputDir = Paths.get(outputDirStr);
		Path zipFile = outputDir.resolve(zipFilename);

		// 1. 임시 ZIP 파일 압축 처리
		createZipArchive(zipFile, instances, storageRootStr);

		// 2. DB 저장 프로시저 호출
		registerArchiveInDb(study, zipFile, zipFilename);

		return true;
	}

	/**
	 * 임시 ZIP 아카이브 압축 파일 생성을 담당하는 헬퍼 메서드
	 */
	private void createZipArchive(Path zipFile, List<Instance> instances, String storageRootStr) {
		log.info("아카이브 ZIP 압축 생성 시작 (파일명: {})", zipFile.getFileName());

		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
			Path storageRoot = Paths.get(storageRootStr);
			int index = 0;

			for (Instance ins : instances) {
				index++;
				Path path = storageRoot.resolve(ins.getLocationRoot()).resolve(ins.getLocationPath());

				if (!Files.exists(path) || !Files.isReadable(path)) {
					log.error("> [{}/{}] 압축 실패 (파일 없음/읽기 불가) - InstanceKey: {}, Path: {}", index, instances.size(), ins.getDcmInstanceKey(), path.toAbsolutePath());

					throw new DicomErrorException(DicomErrorCode.INSTANCE_FILE_NOT_FOUND,
							String.format("아카이브 파일 압축 중 물리 파일을 읽을 수 없습니다. (InstanceKey: %d, Path: %s)", ins.getDcmInstanceKey(), path.toAbsolutePath()));
				}

				int seriesNo = ins.getHdcmSeries().getSeriesNo() != null ? ins.getHdcmSeries().getSeriesNo().intValue() : 0;
				int instanceNo = ins.getInstanceNo() != null ? ins.getInstanceNo().intValue() : 0;

				String entryName = String.format("S%04d_I%04d_%s.dcm", seriesNo, instanceNo, ins.getSopInstanceUid());
				ZipEntry zipEntry = new ZipEntry(entryName);
				zos.putNextEntry(zipEntry);

				Files.copy(path, zos);
				zos.closeEntry();
				log.debug("> [{}/{}] 압축 추가 완료 - InstanceKey: {}, Entry: {}",
						index, instances.size(), ins.getDcmInstanceKey(), entryName);
			}
		} catch (IOException e) {
			log.error("ZIP 파일 생성 중 I/O 오류 발생 (ZipFile: {})", zipFile.toAbsolutePath(), e);
			deleteZipFile(zipFile);

			throw new DicomErrorException(DicomErrorCode.ZIP_CREATION_FAILED, e);
		}
	}

	/**
	 * 아카이브 정보를 데이터베이스 등록 프로시저 호출을 통해 저장하는 헬퍼 메서드
	 */
	private void registerArchiveInDb(Study study, Path zipFile, String zipFilename) {
		try {
			long zipFileSize = Files.size(zipFile);
			log.info("아카이브 DB 등록 프로시저 호출 (파일명: {}, 크기: {} bytes)", zipFilename, zipFileSize);

			Long archiveKey = studyRepository.createStudyArchive(study.getDcmStudyKey(), zipFilename, zipFileSize);
			if (archiveKey == null || archiveKey <= 0) {
				log.error("아카이브 DB 등록 실패 (반환 키 유효하지 않음) - StudyKey: {}", study.getDcmStudyKey());
				deleteZipFile(zipFile);

				throw new DicomErrorException(DicomErrorCode.DB_ARCHIVE_FAILED);
			}

			log.info("아카이브 DB 등록 성공 (ArchiveKey: {})", archiveKey);
		} catch (DicomErrorException e) {
			throw e;
		} catch (Exception e) {
			log.error("아카이브 등록 중 오류 발생, 생성된 ZIP 제거 및 롤백 진행", e);
			deleteZipFile(zipFile);

			throw new DicomErrorException(DicomErrorCode.DB_ARCHIVE_FAILED, e);
		}
	}

	/**
	 * [3단계 - Cleanup] 원본 데이터베이스 레코드를 삭제하고 디스크 삭제 대기열(Purge Queue)에 등록합니다.
	 */
	@Transactional("postgresTransactionManager")
	public void cleanupStudy(Integer studyKey) {
		try {
			log.info("원본 데이터 삭제 및 Purge Queue 등록 프로시저 호출 (StudyKey: {})", studyKey);
			studyRepository.removeOriginalStudy(studyKey);
			log.info("원본 데이터 삭제 프로시저 완료 (StudyKey: {})", studyKey);
		} catch (Exception e) {
			log.error("원본 데이터 삭제 프로시저 실패 (StudyKey: {})", studyKey, e);

			throw new DicomErrorException(DicomErrorCode.DB_CLEANUP_FAILED, e);
		}
	}

	private void deleteZipFile(Path zipFile) {
		try {
			boolean deleted = Files.deleteIfExists(zipFile);

			if (deleted) {
				log.info("임시 생성된 ZIP 파일 삭제 완료: {}", zipFile.toAbsolutePath());
			}
		} catch (IOException ex) {
			log.error("임시 ZIP 파일 삭제 실패: {}", zipFile.toAbsolutePath(), ex);
		}
	}
}
