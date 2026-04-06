package dev.ioexception.dicom.controller;

import dev.ioexception.dicom.common.DicomParserUtil;
import dev.ioexception.dicom.controller.swagger.DicomApiDocs;
import dev.ioexception.dicom.dto.DicomUidResponse;
import dev.ioexception.dicom.service.DicomForwardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
public class DicomController implements DicomApiDocs {
    private final DicomForwardService dicomForwardService;

    @Override
    public ResponseEntity<String> forwardDicomFilesAsync(
            @RequestParam("sourceId") String sourceId,
            @RequestParam("files") List<MultipartFile> files) {

        DicomUidResponse uids = DicomParserUtil.extractUid(files.getFirst());

        if (uids == null || uids.studyUid() == null || uids.studyUid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DICOM 파일에서 UID 정보를 찾을 수 없습니다.");
        }

        List<String> results = dicomForwardService.forwardFilesAsync(files, uids.studyUid(), sourceId);

        String responseUid = "=== WADO 테스트용 UID (첫 번째 파일 기준) ===\n" +
                "StudyUID: " + uids.studyUid() + "\n" +
                "SeriesUID: " + uids.seriesUid() + "\n" +
                "ObjectUID(SOP): " + uids.sopInstanceUid() + "\n\n" +
                "=== 전송 결과 ===\n" +
                String.join("\n", results);

        return ResponseEntity.ok(responseUid);
    }

    @Override
    public ResponseEntity<byte[]> getWadoImage(
            @RequestParam("studyUID") String studyUID,
            @RequestParam("seriesUID") String seriesUID,
            @RequestParam("objectUID") String objectUID,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "contentType", defaultValue = "image/jpeg") String contentType) {

        try {
            byte[] imageBytes = dicomForwardService.getWadoImage(studyUID, seriesUID, objectUID, sourceId, contentType);

            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();

            if ("application/dicom".equals(contentType)) {
                responseBuilder.contentType(MediaType.parseMediaType("application/dicom"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + objectUID + ".dcm\"");
            } else {
                responseBuilder.contentType(MediaType.IMAGE_JPEG);
            }

            return responseBuilder.body(imageBytes);

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public ResponseEntity<byte[]> downloadStudyZip(
            @PathVariable String studyUID,
            @RequestParam("patientId") String patientId) {

        try {
            byte[] zipBytes = dicomForwardService.downloadStudyZip(studyUID, patientId);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"study_" + studyUID + ".zip\"")
                    .body(zipBytes);

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public ResponseEntity<String> retrieveStudyMetadata(
            @PathVariable("studyUID") String studyUID,
            @RequestParam("patientId") String patientId,
            @RequestParam(value = "format", defaultValue = "json") String format,
            @RequestParam(value = "includePrivate", required = false, defaultValue = "yes") String includePrivate,
            @RequestParam(value = "groups", required = false) String groups,
            @RequestParam(value = "xsl", required = false) String xsl) {

        try {
            String acceptHeader;
            MediaType responseMediaType;

            if ("xml".equalsIgnoreCase(format)) {
                acceptHeader = "multipart/related;type=application/dicom+xml";
                responseMediaType = MediaType.APPLICATION_XML;
            } else if ("html".equalsIgnoreCase(format)) {
                acceptHeader = "multipart/related;type=text/html";
                responseMediaType = MediaType.TEXT_HTML;
            } else {
                acceptHeader = "application/json";
                responseMediaType = MediaType.APPLICATION_JSON;
            }

            String metadata = dicomForwardService.retrieveStudyMetadata(
                    studyUID, patientId, acceptHeader, includePrivate, groups, xsl
            );

            return ResponseEntity.ok()
                    .contentType(responseMediaType)
                    .body(metadata);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public ResponseEntity<String> createDicomManifestKOS(
            @PathVariable("studyUID") String studyUid,
            @PathVariable("kosUID") String kosUid,
            @RequestParam("sourceId") String sourceId,
            @RequestParam(value = "hasReport", defaultValue = "false") boolean hasReport,
            @RequestParam(value = "totalInstanceCount", required = false) Integer totalInstanceCount) {

        try {
            dicomForwardService.createDicomManifestKOS(studyUid, kosUid, sourceId, hasReport, totalInstanceCount);

            return ResponseEntity.ok("KOS 문서가 성공적으로 생성 및 등록되었습니다.");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
