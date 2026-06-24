package dev.ioexception.dicom.common;

import dev.ioexception.dicom.dto.response.DicomUidResponse;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
public class DicomParserUtil {
    public static DicomUidResponse extractUid(MultipartFile file) {
        try (DicomInputStream dis = new DicomInputStream(file.getInputStream())) {
            // 영상 데이터는 읽지 않고 건너뛰도록 설정
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);

            Attributes dataset = dis.readDataset();

            return new DicomUidResponse(
                    dataset.getString(Tag.StudyInstanceUID),
                    dataset.getString(Tag.SeriesInstanceUID),
                    dataset.getString(Tag.SOPInstanceUID)
            );
        } catch (IOException e) {
            log.error("DICOM 파일 파싱 중 에러 발생: {}", file.getOriginalFilename(), e);

            return null;
        }
    }
}
