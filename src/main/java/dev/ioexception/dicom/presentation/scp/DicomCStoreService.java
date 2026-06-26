package dev.ioexception.dicom.presentation.scp;

import dev.ioexception.dicom.aop.DicomMdcLog;
import dev.ioexception.dicom.service.DicomFileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.pdu.PresentationContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
@RequiredArgsConstructor
public class DicomCStoreService {

    private final DicomFileStorageService storageService;

    @DicomMdcLog
    public void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data, Attributes rsp)
            throws IOException {
        log.info("--- [C-STORE 파일 수신 시작] SCU: {} ---", as.getCallingAET());
        Path tempFilePath = null;

        try {
            // 1. 스트림을 임시 파일로 저장 (네트워크 I/O)
            tempFilePath = Files.createTempFile("dicom_temp_", ".dcm");
            Files.copy(data, tempFilePath, StandardCopyOption.REPLACE_EXISTING);

            // 2. 비즈니스 로직(파싱, 경로 이동)은 Service에게 위임
            storageService.processAndSaveDicom(tempFilePath, rq);

            log.info("--- [C-STORE 파일 수신 정상 종료] ---");
        } catch (Exception e) {
            log.error("DICOM 처리 실패: {}", e.getMessage(), e);
            throw new IOException("C-STORE 프로세스 실패", e);
        } finally {
            // 3. 에러가 나거나 이동에 실패해 임시 파일이 남아있다면 삭제 보장
            if (tempFilePath != null && Files.exists(tempFilePath)) {
                try {
                    Files.deleteIfExists(tempFilePath);
                } catch (IOException e) {
                    log.warn("임시 파일 삭제 실패: {}", tempFilePath);
                }
            }
        }
    }
}
