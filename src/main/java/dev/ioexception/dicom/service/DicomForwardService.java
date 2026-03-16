package dev.ioexception.dicom.service;

import dev.ioexception.dicom.common.DicomXmlParserUtil;
import dev.ioexception.dicom.config.DicomClient;
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
public class DicomForwardService {
    private final DicomClient dicomClient;

    public List<String> forwardFilesAsync(List<MultipartFile> files, String studyUid, String sourceId) {
        log.info("총 {} 개의 파일 비동기 전송 시작 (Study: {}, Source: {})", files.size(), studyUid, sourceId);

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<String>> futures = files.stream().map(file -> {
                try {
                    byte[] fileBytes = file.getBytes();
                    String filename = file.getOriginalFilename();
                    return CompletableFuture.supplyAsync(() -> sendSingleFile(fileBytes, filename, studyUid, sourceId), virtualExecutor);
                } catch (Exception e) {
                    log.error("[{}] 파일 읽기 오류 발생", file.getOriginalFilename(), e);
                    return CompletableFuture.completedFuture(String.format("[%s] 파일 읽기 실패: %s", file.getOriginalFilename(), e.getMessage()));
                }
            }).toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
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

            String rawXmlResponse = dicomClient.sendDicom(studyUid, sourceId, body);
            String parsedResult = DicomXmlParserUtil.extractSuccessInfo(rawXmlResponse);

            log.info("[{}] 전송 성공: {}", filename, parsedResult);

            return String.format("[%s] 성공 -> %s", filename, parsedResult);
        } catch (Exception e) {
            log.error("[{}] 전송 실패", filename, e);

            return String.format("[%s] 실패 -> %s", filename, e.getMessage());
        }
    }
}
