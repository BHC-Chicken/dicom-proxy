package dev.ioexception.dicom.controller.swagger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RequestMapping("/api/dicom")
@Tag(name = "DICOM API", description = "DICOM 파일 중계 API")
public interface DicomApiDocs {
    @Operation(summary = "DICOM 다중 파일 비동기 중계", description = "여러 장의 DICOM 파일을 받아 타겟 서버로 병렬 전송합니다.")
    @PostMapping(value = "/forward-async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<String> forwardDicomFilesAsync(

            @Parameter(description = "요청 출처 OID", example = "1.2.410.100110.10.99999981")
            @RequestParam("sourceId") @NotBlank(message = "source id가 없습니다.") String sourceId,

            @Parameter(description = "전송할 DICOM 파일 목록 (.dcm)")
            @RequestParam("files") @NotEmpty(message = "전송할 파일이 없습니다.") List<MultipartFile> files
    );
}
