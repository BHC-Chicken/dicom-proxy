package dev.ioexception.dicom.controller;

import dev.ioexception.dicom.controller.swagger.DicomApiDocs;
import dev.ioexception.dicom.dto.MetadataFormat;
import dev.ioexception.dicom.dto.request.PurgeRequest;
import dev.ioexception.dicom.dto.response.DicomForwardResponse;
import dev.ioexception.dicom.dto.response.PurgeSummaryInfoResponse;
import dev.ioexception.dicom.service.DicomPurgeService;
import dev.ioexception.dicom.service.DicomWebService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
public class DicomController implements DicomApiDocs {
    private final ObjectProvider<DicomPurgeService> dicomPurgeServiceProvider;
    private final DicomWebService dicomWebService;

    @Override
    public ResponseEntity<List<DicomForwardResponse>> forwardDicomFilesAsync(
            @RequestParam("sourceId") String sourceId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            HttpServletRequest request) {

        List<DicomForwardResponse> result = dicomWebService.processAndForwardDicomAsync(sourceId, files, request);

        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<byte[]> getWadoImage(
            @RequestParam("studyUID") String studyUID,
            @RequestParam("seriesUID") String seriesUID,
            @RequestParam("objectUID") String objectUID,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "contentType", defaultValue = "image/jpeg") String contentType) {

        byte[] imageBytes = dicomWebService.getWadoImage(studyUID, seriesUID, objectUID, sourceId, contentType);
        boolean isDicom = "application/dicom".equalsIgnoreCase(contentType);

        return ResponseEntity.ok()
                .contentType(isDicom ? MediaType.parseMediaType("application/dicom") : MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION, isDicom ? "attachment; filename=\"" + objectUID + ".dcm\"" : "inline")
                .body(imageBytes);
    }

    @Override
    public ResponseEntity<byte[]> downloadStudyZip(
            @PathVariable String studyUID,
            @RequestParam("patientId") String patientId) {

        byte[] zipBytes = dicomWebService.downloadStudyZip(studyUID, patientId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"study_" + studyUID + ".zip\"")
                .body(zipBytes);
    }

    @Override
    public ResponseEntity<String> retrieveStudyMetadata(
            @PathVariable("studyUID") String studyUID,
            @RequestParam("patientId") String patientId,
            @RequestParam(value = "format", defaultValue = "json") String format,
            @RequestParam(value = "includePrivate", required = false, defaultValue = "yes") String includePrivate,
            @RequestParam(value = "groups", required = false) String groups,
            @RequestParam(value = "xsl", required = false) String xsl) {

        MetadataFormat metadataFormat = MetadataFormat.from(format);
        String metadata = dicomWebService.retrieveStudyMetadata(
                studyUID, patientId, metadataFormat, includePrivate, groups, xsl);

        return ResponseEntity.ok()
                .contentType(metadataFormat.getMediaType())
                .body(metadata);
    }

    @Override
    public ResponseEntity<String> createDicomManifestKOS(
            @PathVariable("studyUID") String studyUid,
            @PathVariable("kosUID") String kosUid,
            @RequestParam("sourceId") String sourceId,
            @RequestParam(value = "hasReport", defaultValue = "false") boolean hasReport,
            @RequestParam(value = "totalInstanceCount", required = false) Integer totalInstanceCount) {

        dicomWebService.createDicomManifestKOS(studyUid, kosUid, sourceId, hasReport, totalInstanceCount);

        return ResponseEntity.ok("KOS 문서가 성공적으로 생성 및 등록되었습니다.");
    }

    @Override
    public ResponseEntity<PurgeSummaryInfoResponse> purgeDicom(@RequestBody PurgeRequest purgeRequest) {
        DicomPurgeService dicomPurgeService = dicomPurgeServiceProvider.getIfAvailable();
        if (dicomPurgeService == null) {
            throw new IllegalStateException("Purge 기능이 비활성화되어 있습니다. (dicom.purge.enabled=true 필요)");
        }
        PurgeSummaryInfoResponse response = dicomPurgeService.executePurgeProcess(purgeRequest);

        return ResponseEntity.ok(response);
    }
}
