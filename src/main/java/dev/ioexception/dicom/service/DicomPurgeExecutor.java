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
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dicom.purge.enabled", havingValue = "true", matchIfMissing = false)
public class DicomPurgeExecutor {

	private final StudyRepository studyRepository;
	private final ReentrantLock[] archiveLocks = Stream.generate(ReentrantLock::new)
			.limit(64)
			.toArray(ReentrantLock[]::new);

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
	@Transactional
	public boolean archiveStudyFiles(Study study, List<Instance> instances, String storageRootStr, String outputDirStr) {
		String archiveId = study.getStudyInstanceUid();
		ReentrantLock archiveLock = archiveLocks[Math.floorMod(archiveId.hashCode(), archiveLocks.length)];
		archiveLock.lock();
		try {
			return archiveStudyFilesLocked(study, instances, storageRootStr, outputDirStr);
		} finally {
			archiveLock.unlock();
		}
	}

	private boolean archiveStudyFilesLocked(Study study, List<Instance> instances, String storageRootStr, String outputDirStr) {
		String zipFilename = String.format("STD_%s.zip", study.getStudyInstanceUid());
		Path outputDir = Paths.get(outputDirStr);
		Path zipFile = outputDir.resolve(zipFilename);
		Path partFile = null;

		try {
			Files.createDirectories(outputDir);
			if (Files.exists(zipFile)) {
				throw new DicomErrorException(DicomErrorCode.ZIP_CREATION_FAILED,
						"기존 아카이브 파일이 존재합니다: " + zipFile.toAbsolutePath());
			}

			partFile = Files.createTempFile(outputDir, zipFilename + ".", ".part");
			createZipArchive(partFile, instances, storageRootStr);
			verifyZipArchive(partFile, instances.size());

			publishArchiveNoReplace(partFile, zipFile);
			// The final name is now a hard link to the verified inode. Removing the
			// temporary name cannot affect the published archive.
			deleteZipFile(partFile);

			registerArchiveInDb(study, zipFile, zipFilename);

			return true;
		} catch (DicomErrorException e) {
			throw e;
		} catch (IOException e) {
			log.error("ZIP 아카이브 생성 또는 이동 중 I/O 오류 발생 (ZipFile: {})", zipFile.toAbsolutePath(), e);
			throw new DicomErrorException(DicomErrorCode.ZIP_CREATION_FAILED, e);
		} finally {
			if (partFile != null) {
				deleteZipFile(partFile);
			}
		}
	}

	/**
	 * Publishes a verified archive without a check-then-move race. Creating a hard
	 * link is one atomic namespace operation and fails if {@code zipFile} already
	 * exists, including when another JVM publishes the same Study concurrently.
	 * Both paths are created in the same directory by the caller.
	 */
	void publishArchiveNoReplace(Path partFile, Path zipFile) {
		try {
			Files.createLink(zipFile, partFile);
		} catch (FileAlreadyExistsException e) {
			throw new DicomErrorException(DicomErrorCode.ZIP_CREATION_FAILED,
					"기존 아카이브 파일이 존재합니다: " + zipFile.toAbsolutePath());
		} catch (UnsupportedOperationException e) {
			log.error("파일 시스템이 원자적 no-replace 아카이브 publish를 지원하지 않습니다: {}",
					zipFile.toAbsolutePath(), e);
			throw new DicomErrorException(DicomErrorCode.ZIP_CREATION_FAILED, e);
		} catch (IOException e) {
			log.error("원자적 no-replace 아카이브 publish 실패 (PartFile: {}, ZipFile: {})",
					partFile.toAbsolutePath(), zipFile.toAbsolutePath(), e);
			throw new DicomErrorException(DicomErrorCode.ZIP_CREATION_FAILED, e);
		}
	}

	/**
	 * 임시 ZIP 아카이브 압축 파일 생성을 담당하는 헬퍼 메서드
	 */
	private void createZipArchive(Path partFile, List<Instance> instances, String storageRootStr) {
		log.info("아카이브 ZIP 압축 생성 시작 (임시 파일명: {})", partFile.getFileName());

		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(partFile))) {
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
			log.error("ZIP 파일 생성 중 I/O 오류 발생 (PartFile: {})", partFile.toAbsolutePath(), e);

			throw new DicomErrorException(DicomErrorCode.ZIP_CREATION_FAILED, e);
		}
	}

	private void verifyZipArchive(Path partFile, int expectedEntryCount) throws IOException {
		int actualEntryCount = 0;
		try (ZipFile zipFile = new ZipFile(partFile.toFile())) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				try (var inputStream = zipFile.getInputStream(entry)) {
					inputStream.transferTo(OutputStream.nullOutputStream());
				}
				actualEntryCount++;
			}
		}

		if (actualEntryCount != expectedEntryCount) {
			throw new IOException(String.format("ZIP 엔트리 수 불일치 (expected=%d, actual=%d)",
					expectedEntryCount, actualEntryCount));
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
			log.error("아카이브 등록 결과를 확정할 수 없어 ZIP을 reconciliation 대상으로 보존합니다: {}",
					zipFile.toAbsolutePath(), e);

			throw new DicomErrorException(DicomErrorCode.DB_ARCHIVE_FAILED, e);
		}
	}

	/**
	 * [3단계 - Cleanup] 원본 데이터베이스 레코드를 삭제하고 디스크 삭제 대기열(Purge Queue)에 등록합니다.
	 */
	@Transactional
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
