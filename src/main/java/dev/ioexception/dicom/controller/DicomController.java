package dev.ioexception.dicom.controller;

import dev.ioexception.dicom.common.DicomParserUtil;
import dev.ioexception.dicom.controller.swagger.DicomApiDocs;
import dev.ioexception.dicom.service.DicomForwardService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
            @RequestParam("sourceId") @NotBlank(message = "source id가 없습니다.") String sourceId,
            @RequestParam("files") @NotEmpty(message = "전송할 파일이 없습니다.") List<MultipartFile> files) {
        String extractedStudyUid = DicomParserUtil.extractStudyUid(files.getFirst());
        if (extractedStudyUid == null || extractedStudyUid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DICOM 파일에서 Study UID를 찾을 수 없습니다.");
        }

        List<String> results = dicomForwardService.forwardFilesAsync(files, extractedStudyUid, sourceId);
        String finalResponse = String.join("\n", results);

        return ResponseEntity.ok(finalResponse);
    }
}
