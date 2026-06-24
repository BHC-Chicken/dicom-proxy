package dev.ioexception.dicom.service;

import dev.ioexception.dicom.common.DicomXmlParserUtil;
import dev.ioexception.dicom.config.dicom.DicomDcmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DicomWebService {
	private final DicomDcmClient dicomDcmClient;

	public List<String> forwardFilesAsync(List<MultipartFile> files, String studyUid, String sourceId) {
		log.info("총 {} 개의 파일 비동기 전송 시작 (Study: {}, Source: {})", files.size(), studyUid, sourceId);

		try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<String>> futures = files.stream().map(file -> {
				try {
					byte[] fileBytes = file.getBytes();
					String filename = file.getOriginalFilename();

					return CompletableFuture.supplyAsync(() ->
									sendSingleFile(fileBytes, filename, studyUid, sourceId), virtualExecutor);
				} catch (Exception e) {
					log.error("[{}] 파일 읽기 오류 발생", file.getOriginalFilename(), e);

					throw new RuntimeException("Discord Webhook 테스트를 위한 강제 에러 발생!");
				}
			}).toList();

			return futures.stream()
					.map(CompletableFuture::join)
					.toList();
		}
	}

	public byte[] getWadoImage(String studyUid, String seriesUid, String objectUid, String sourceId, String contentType) {
		log.info("WADO 조회 요청 (Study: {}, Series: {}, Object: {}, Source: {}, ContentType: {})", studyUid, seriesUid, objectUid, sourceId, contentType);

		try {
			return dicomDcmClient.getWadoImage(
					"WADO",
					studyUid,
					seriesUid,
					objectUid,
					contentType,
					sourceId
			);
		} catch (Exception e) {
			log.error("WADO 이미지 조회 실패 (Study: {}, Object: {})", studyUid, objectUid, e);

			throw new RuntimeException("WADO 데이터를 불러오는 데 실패했습니다.", e);
		}
	}

	public byte[] downloadStudyZip(String studyUid, String patientId) {
		log.info("Study ZIP 다운로드 요청 (Study: {}, PatientID: {})", studyUid, patientId);

		try {

			return dicomDcmClient.downloadStudyZip(studyUid, patientId);
		} catch (Exception e) {
			log.error("Study ZIP 다운로드 실패 (Study: {}, PatientID: {})", studyUid, patientId, e);

			throw new RuntimeException("Study ZIP 데이터를 불러오는 데 실패했습니다.", e);
		}
	}

	public void createDicomManifestKOS(String studyUid, String kosUid, String sourceId, boolean hasReport, Integer totalInstanceCount) {
		log.info("KOS 생성 요청 (Study: {}, KOS: {}, SourceID: {})", studyUid, kosUid, sourceId);

		try {
			String countStr = (totalInstanceCount != null) ? String.valueOf(totalInstanceCount) : null;

			dicomDcmClient.createDicomManifestKOS(
					studyUid,
					kosUid,
					sourceId,
					String.valueOf(hasReport),
					countStr
			);
		} catch (Exception e) {
			log.error("KOS 생성 및 등록 실패 (Study: {}, KOS: {})", studyUid, kosUid, e);

			throw new RuntimeException("KOS 문서를 생성하고 등록하는 데 실패했습니다.", e);
		}
	}

	public String retrieveStudyMetadata(String studyUid, String patientId, String acceptHeader,
	                                    String includePrivate, String groups, String xsl) {
		log.info("Study Metadata 조회 요청 (Study: {}, PatientID: {}, Accept: {}, Private: {}, Groups: {}, XSL: {})",
				studyUid, patientId, acceptHeader, includePrivate, groups, xsl);

		try {
			return dicomDcmClient.retrieveStudyMetadata(studyUid, acceptHeader, patientId, includePrivate, groups, xsl);
		} catch (Exception e) {
			log.error("Study Metadata 조회 실패 (Study: {}, Format: {})", studyUid, acceptHeader, e);
			throw new RuntimeException("Study Metadata를 불러오는 데 실패했습니다.", e);
		}
	}

	private String sendSingleFile(byte[] fileBytes, String filename, String studyUid, String sourceId) {
		try {
			MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

			HttpHeaders partHeaders = new HttpHeaders();
			partHeaders.setContentType(MediaType.parseMediaType("application/dicom"));

			ByteArrayResource resource = new ByteArrayResource(fileBytes) {
				@Override
				public String getFilename() {
					log.info("filename: {}", filename);

					return filename;
				}
			};

			body.add("file", new HttpEntity<>(resource, partHeaders));

			String rawXmlResponse = dicomDcmClient.sendDicom(studyUid, sourceId, body);
			String parsedResult = DicomXmlParserUtil.extractSuccessInfo(rawXmlResponse);

			log.info("[{}] 전송 성공: {}", filename, parsedResult);

			return String.format("[%s] 성공 -> %s", filename, parsedResult);
		} catch (Exception e) {
			log.error("[{}] 전송 실패", filename, e);

			return String.format("[%s] 실패 -> %s", filename, e.getMessage());
		}
	}
}
