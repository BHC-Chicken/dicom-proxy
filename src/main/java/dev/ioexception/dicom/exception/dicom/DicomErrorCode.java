package dev.ioexception.dicom.exception.dicom;

import dev.ioexception.dicom.exception.GlobalErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DicomErrorCode implements GlobalErrorCode {
	NOT_FOUND_STUDY(HttpStatus.NOT_FOUND, "존재하지 않는 study 입니다.", Level.INFO),
	NOT_FOUND_SERIES(HttpStatus.NOT_FOUND, "존재하지 않는 study 입니다.", Level.INFO),
	NOT_FOUND_INSTANCE(HttpStatus.NOT_FOUND, "존재하지 않는 study 입니다.", Level.INFO),
	NOT_FOUND_STUDY_LIST(HttpStatus.NOT_FOUND, "조회 기간 내에 해당하는 Study 목록이 존재하지 않습니다.", Level.INFO),
	ZIP_CREATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "ZIP 아카이브 압축 파일 생성에 실패했습니다.", Level.ERROR),
	DB_ARCHIVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "아카이브 DB 등록 프로시저 호출에 실패했습니다.", Level.ERROR),
	DB_CLEANUP_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "원본 데이터 삭제 프로시저 호출에 실패했습니다.", Level.ERROR),
	INSTANCE_FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "인스턴스 물리 파일이 존재하지 않거나 읽을 수 없습니다.", Level.ERROR),
	INSTANCE_SIZE_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "인스턴스 물리 파일 크기가 DB 정보와 일치하지 않습니다.", Level.ERROR);

	private final HttpStatus httpStatus;
	private final String message;
	private final Level level;
}
