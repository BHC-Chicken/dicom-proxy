package dev.ioexception.dicom.config;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange("/hime-server")
public interface DicomClient {

    @PostExchange(
            value = "/dcm/studies/{studyUid}",
            contentType = "multipart/form-data",
            accept = "application/dicom+xml"
    )
    String sendDicom(
            @PathVariable String studyUid,
            @RequestParam("SourceID") String sourceId,
            @RequestBody MultiValueMap<String, Object> body
    );
}
