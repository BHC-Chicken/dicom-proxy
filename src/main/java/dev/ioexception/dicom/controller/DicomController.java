package dev.ioexception.dicom.controller;

import dev.ioexception.dicom.controller.swagger.DicomApiDocs;
import dev.ioexception.dicom.dto.MetadataFormat;
import dev.ioexception.dicom.dto.dicom.response.DicomStreamResponse;
import dev.ioexception.dicom.service.dicom.DicomWebService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Transaction;
import org.slf4j.MDC;

import dev.ioexception.dicom.domain.job.DicomJob;
import dev.ioexception.dicom.dto.dicom.response.DicomJobResponse;
import dev.ioexception.dicom.service.dicom.DicomJobService;

import java.net.URI;
import java.util.List;


@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
public class DicomController implements DicomApiDocs {
    private final DicomWebService dicomWebService;
    private final DicomJobService dicomJobService;

    @Override
    public ResponseEntity<DicomJobResponse> submitForwardJob(
            @RequestParam("sourceId") String sourceId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            HttpServletRequest request) {

        DicomJob job = dicomJobService.submitJob(sourceId, files, request);
        URI location = URI.create("/api/dicom/jobs/" + job.getJobId());
        var responseBuilder = ResponseEntity.accepted().location(location);
        String traceId = resolveCurrentTraceId();
        if (traceId != null) {
            responseBuilder.header("X-Trace-Id", traceId);
        }
        return responseBuilder.body(DicomJobResponse.from(job));
    }

    @Override
    public ResponseEntity<DicomJobResponse> getJobStatus(@PathVariable("jobId") String jobId) {
        String traceId = resolveCurrentTraceId();
        return dicomJobService.getJob(jobId)
                .map(job -> {
                    var responseBuilder = ResponseEntity.ok();
                    if (traceId != null) {
                        responseBuilder.header("X-Trace-Id", traceId);
                    }
                    return responseBuilder.body(DicomJobResponse.from(job));
                })
                .orElseGet(() -> {
                    var responseBuilder = ResponseEntity.notFound();
                    if (traceId != null) {
                        responseBuilder.header("X-Trace-Id", traceId);
                    }
                    return responseBuilder.build();
                });
    }

    @Override
    public ResponseEntity<DicomJobResponse> forwardDicomFilesAsync(
            @RequestParam("sourceId") String sourceId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "redirect", defaultValue = "false") boolean redirect,
            HttpServletRequest request) {

        if (redirect) {
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(URI.create("/api/dicom/jobs"))
                    .build();
        }

        DicomJob job = dicomJobService.submitJob(sourceId, files, request);
        URI location = URI.create("/api/dicom/jobs/" + job.getJobId());
        var responseBuilder = ResponseEntity.accepted()
                .location(location)
                .header("Deprecation", "true")
                .header("Link", "</api/dicom/jobs>; rel=\"successor-version\"");
        String traceId = resolveCurrentTraceId();
        if (traceId != null) {
            responseBuilder.header("X-Trace-Id", traceId);
        }
        return responseBuilder.body(DicomJobResponse.from(job));
    }

    private String resolveCurrentTraceId() {
        try {
            Transaction tx = ElasticApm.currentTransaction();
            if (tx != null) {
                String traceId = tx.getTraceId();
                if (traceId != null && !traceId.isBlank()) {
                    return traceId;
                }
            }
        } catch (Throwable ignored) {
        }
        String mdcTraceId = MDC.get("trace.id");
        if (mdcTraceId != null && !mdcTraceId.isBlank()) {
            return mdcTraceId;
        }
        return null;
    }

    @Override
    public ResponseEntity<StreamingResponseBody> getWadoImage(
            @RequestParam("studyUID") String studyUID,
            @RequestParam("seriesUID") String seriesUID,
            @RequestParam("objectUID") String objectUID,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "contentType", defaultValue = "image/jpeg") String contentType) {

        DicomStreamResponse streamResponse = dicomWebService.getWadoImage(
                studyUID, seriesUID, objectUID, sourceId, contentType);
        boolean isDicom = "application/dicom".equalsIgnoreCase(contentType);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(streamResponse.statusCode())
                .contentType(streamResponse.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        isDicom ? "attachment; filename=\"" + objectUID + ".dcm\"" : "inline");
        if (streamResponse.contentLength() >= 0) {
            responseBuilder.contentLength(streamResponse.contentLength());
        }
        return responseBuilder.body(streamResponse.streamingBody());
    }

    @Override
    public ResponseEntity<StreamingResponseBody> downloadStudyZip(
            @PathVariable String studyUID,
            @RequestParam("patientId") String patientId) {

        DicomStreamResponse streamResponse = dicomWebService.downloadStudyZip(studyUID, patientId);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(streamResponse.statusCode())
                .contentType(streamResponse.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"study_" + studyUID + ".zip\"");
        if (streamResponse.contentLength() >= 0) {
            responseBuilder.contentLength(streamResponse.contentLength());
        }
        return responseBuilder.body(streamResponse.streamingBody());
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

}
