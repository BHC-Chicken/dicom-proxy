package dev.ioexception.dicom.config.dicom;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange("/hime-server/dcm")
public interface DicomDcmClient {

    @PostExchange(
            value = "/studies/{studyUid}",
            contentType = "multipart/form-data",
            accept = "application/dicom+xml"
    )
    String sendDicom(
            @PathVariable String studyUid,
            @RequestParam("SourceID") String sourceId,
            @RequestBody MultiValueMap<String, Object> body
    );

    @GetExchange(value = "/wado")
    byte[] getWadoImage(
            @RequestParam("requestType") String requestType,
            @RequestParam("studyUID") String studyUID,
            @RequestParam("seriesUID") String seriesUID,
            @RequestParam("objectUID") String objectUID,
            @RequestParam("contentType") String contentType,
            @RequestParam(value = "SourceID", required = false) String sourceId
    );

    @GetExchange(value = "/studies/{StudyInstanceUID}/zip", accept = "application/zip")
    byte[] downloadStudyZip(
            @PathVariable("StudyInstanceUID") String studyInstanceUid,
            @RequestParam("PatientID") String patientId
    );

    @GetExchange(value = "/studies/{StudyInstanceUID}/kos/{KOSInstanceUID}")
    void createDicomManifestKOS(
            @PathVariable("StudyInstanceUID") String studyInstanceUid,
            @PathVariable("KOSInstanceUID") String kosInstanceUid,
            @RequestParam("SourceID") String sourceId,
            @RequestParam(value = "hasReport", required = false) String hasReport,
            @RequestParam(value = "totalInstanceCount", required = false) String totalInstanceCount
    );

    @GetExchange(value = "/studies/{StudyInstanceUID}/metadata")
    String retrieveStudyMetadata(
            @PathVariable("StudyInstanceUID") String studyInstanceUid,
            @RequestHeader("Accept") String acceptHeader,
            @RequestParam("PatientID") String patientId,
            @RequestParam(value = "private", required = false) String includePrivate,
            @RequestParam(value = "groups", required = false) String groups,
            @RequestParam(value = "xsl", required = false) String xsl
    );
}
