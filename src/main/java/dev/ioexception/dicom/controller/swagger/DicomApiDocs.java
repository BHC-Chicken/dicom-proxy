package dev.ioexception.dicom.controller.swagger;

import dev.ioexception.dicom.dto.request.PurgeRequest;
import dev.ioexception.dicom.dto.response.DicomForwardResponse;
import dev.ioexception.dicom.dto.response.PurgeSummaryInfoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@RequestMapping("/api/dicom")
@Tag(name = "DICOM API", description = "DICOM bounded-memory 스트리밍 프록시 API")
public interface DicomApiDocs {

	@Operation(summary = "DICOM bounded-memory 스트리밍 중계 (.dat / STOW-RS)", description = "요청을 파일 기반으로 검증해 하나의 Study만 포함하는지 확인한 뒤, 전체 본문을 힙에 적재하지 않고 타겟 DICOM 서버로 전달합니다.")
	@PostMapping(value = "/forward-async", consumes = {
			MediaType.MULTIPART_FORM_DATA_VALUE,
			"multipart/related",
			MediaType.APPLICATION_OCTET_STREAM_VALUE,
			MediaType.ALL_VALUE
	})
	ResponseEntity<List<DicomForwardResponse>> forwardDicomFilesAsync(
			@Parameter(description = "요청 출처 OID", example = "1.2.410.100110.10.99999981") @RequestParam("sourceId") @NotBlank(message = "source id가 없습니다.") String sourceId,
			@Parameter(description = "전송할 .dat 패키지 파일 목록 (Swagger UI 다중 파일 첨부 가능)") @RequestPart(value = "files", required = false) List<MultipartFile> files,
			HttpServletRequest request);

	@Operation(summary = "WADO 이미지 조회", description = "UID 값들을 이용해 중계 서버를 거쳐 타겟 서버의 DICOM 데이터를 JPEG 또는 DCM 파일로 조회합니다.")
	@GetMapping(value = "/wado", produces = {MediaType.IMAGE_JPEG_VALUE, "application/dicom"})
	ResponseEntity<StreamingResponseBody> getWadoImage(
			@Parameter(description = "Study Instance UID") @RequestParam("studyUID") String studyUID,
			@Parameter(description = "Series Instance UID") @RequestParam("seriesUID") String seriesUID,
			@Parameter(description = "SOP Instance UID (Object UID)") @RequestParam("objectUID") String objectUID,
			@Parameter(description = "요청 출처 OID", example = "1.2.410.100110.10.99999981") @RequestParam(value = "sourceId", required = false) String sourceId,
			@Parameter(description = "조회할 데이터 타입 (image/jpeg 또는 application/dicom)", example = "image/jpeg") @RequestParam(value = "contentType", defaultValue = "image/jpeg") String contentType);

	@Operation(summary = "Study 단위 DICOM ZIP 다운로드", description = "특정 Study에 포함된 모든 DICOM 파일들을 하나의 ZIP 파일로 다운로드합니다.")
	@GetMapping(value = "/studies/{studyUID}/zip", produces = "application/zip")
	ResponseEntity<StreamingResponseBody> downloadStudyZip(
			@PathVariable @Parameter(description = "Study Instance UID") String studyUID,
			@Parameter(description = "환자 ID (필수)", example = "12345678") @RequestParam("patientId") String patientId);

	@Operation(summary = "DICOM Manifest KOS 생성 및 등록", description = "특정 Study에 대한 KOS 문서를 생성하고 XDS Repository에 등록합니다.")
	@GetMapping(value = "/studies/{studyUID}/kos/{kosUID}")
	ResponseEntity<String> createDicomManifestKOS(
			@PathVariable @Parameter(description = "Study Instance UID") String studyUID,
			@PathVariable @Parameter(description = "발급된 KOS Instance UID") String kosUID,
			@Parameter(description = "요청 출처 OID (SourceID)", example = "1.2.410.100110.10.99999981") @RequestParam("sourceId") String sourceId,
			@Parameter(description = "판독 리포트 존재 여부") @RequestParam(value = "hasReport", defaultValue = "false") boolean hasReport,
			@Parameter(description = "전체 인스턴스 개수 (선택)") @RequestParam(value = "totalInstanceCount", required = false) Integer totalInstanceCount);

	@Operation(summary = "Study 단위 DICOM 메타데이터 조회", description = "특정 Study에 포함된 DICOM 인스턴스들의 메타데이터를 조회합니다.")
	@GetMapping(value = "/studies/{studyUID}/metadata", produces = {MediaType.APPLICATION_JSON_VALUE,
			MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_HTML_VALUE})
	ResponseEntity<String> retrieveStudyMetadata(
			@Parameter(description = "Study Instance UID") @PathVariable("studyUID") String studyUid,
			@Parameter(description = "환자 ID (필수)", example = "12345678") @RequestParam("patientId") String patientId,
			@Parameter(description = "응답 형식 (json, xml, html)", example = "json") @RequestParam(value = "format", defaultValue = "json") String format,
			@Parameter(description = "Private Tag 포함 여부 (yes 또는 no)", example = "yes") @RequestParam(value = "includePrivate", required = false, defaultValue = "yes") String includePrivate,
			@Parameter(description = "포함할 Group 태그 (Hex 콤마 구분, 예: 0010,0020)") @RequestParam(value = "groups", required = false) String groups,
			@Parameter(description = "XSL 스타일시트 URL (HTML 요청 시 필수)") @RequestParam(value = "xsl", required = false) String xsl);

	@Operation(summary = "DICOM 데이터 영구 정리 (Purge)", description = "지정된 기간과 옵션(정합성 검증, 아카이빙 압축, 삭제)에 따라 대상 Study의 원본 DICOM 데이터를 삭제 및 백업 처리합니다.")
	@PostMapping(value = "/purge", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<PurgeSummaryInfoResponse> purgeDicom(@RequestBody PurgeRequest request);
}
