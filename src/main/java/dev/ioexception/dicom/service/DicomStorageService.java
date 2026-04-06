package dev.ioexception.dicom.service;

import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;

@Slf4j
@Service
public class DicomStorageService {

    @Value("${dicom.scp.base-dir}")
    private String baseDir;

    public void processAndSaveDicom(Path tempFilePath, Attributes rq) throws IOException {
        Attributes dataset = parseDataset(tempFilePath);
        Path finalFilePath = buildFinalPath(dataset, rq);
        moveToFinalDestination(tempFilePath, finalFilePath);
    }

    private Attributes parseDataset(Path tempFilePath) throws IOException {
        try (DicomInputStream dis = new DicomInputStream(tempFilePath.toFile())) {
            // BulkData(픽셀 영상)를 제외하고 텍스트 태그만 읽어 메모리 최적화
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);

            return dis.readDataset();
        }
    }

    private Path buildFinalPath(Attributes dataset, Attributes rq) throws IOException {
        String patientId = dataset.getString(Tag.PatientID, "UNKNOWN_PATIENT");
        String studyDate = dataset.getString(Tag.StudyDate, "UNKNOWN_DATE");
        String studyUid = dataset.getString(Tag.StudyInstanceUID, "UNKNOWN_STUDY");
        String seriesUid = dataset.getString(Tag.SeriesInstanceUID, "UNKNOWN_SERIES");
        String modality = dataset.getString(Tag.Modality, "UNKNOWN_MODALITY");
        String sopUid = dataset.getString(Tag.SOPInstanceUID, rq.getString(Tag.AffectedSOPInstanceUID));

        // 포맷: {baseDir}/{PatientID}/{StudyDate}_{StudyInstanceUID}/{SeriesInstanceUID}_{Modality}/{SOPInstanceUID}.dcm
        String dirPath = String.format("%s/%s_%s/%s_%s", patientId, studyDate, studyUid, seriesUid, modality);
        Path targetDir = Paths.get(baseDir, dirPath);

        // 상위 디렉토리 안전하게 생성 (이미 있으면 무시됨)
        Files.createDirectories(targetDir);

        return targetDir.resolve(sopUid + ".dcm");
    }

    private void moveToFinalDestination(Path source, Path target) throws IOException {
        try {
            // 원자적 이동을 시도 (성공 시 파일이 깨질 위험 원천 차단)
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("최종 파일 저장 완료 (Atomic): {}", target);
        } catch (AtomicMoveNotSupportedException e) {
            // 임시 폴더와 baseDir의 하드디스크 파티션이 다르면 Atomic Move가 불가하므로 일반 이동으로 Fallback 처리
            log.warn("파티션 차이로 인해 ATOMIC_MOVE를 지원하지 않습니다. 일반 복사-이동을 수행합니다.");
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("최종 파일 저장 완료 (Standard): {}", target);
        }
    }
}
