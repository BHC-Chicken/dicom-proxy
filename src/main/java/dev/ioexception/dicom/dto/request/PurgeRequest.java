package dev.ioexception.dicom.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "DICOM 데이터 정리(Purge) 요청 바디 정보")
public record PurgeRequest(
        @Schema(description = "물리 파일이 저장된 로컬 스토리지 루트 경로", example = "/Users/irm/development/dicom-storage")
        String storageRoot,

        @Schema(description = "아카이빙 ZIP 파일이 생성 및 저장될 출력 디렉터리 경로", example = "/Users/irm/development/dicom-archive")
        String outputDir,

        @Schema(description = "퍼지 대상 조회 시작일", example = "2026-06-18")
        LocalDate startTime,

        @Schema(description = "퍼지 대상 조회 종료일 (해당 일자의 전체 데이터 포함)", example = "2026-06-19")
        LocalDate endTime,

        @Schema(description = "1단계: 임시 디렉터리/실제 파일 존재 유무 및 파일 사이즈 정합성 검증 실행 여부", example = "true")
        boolean check,

        @Schema(description = "2단계: 인스턴스들을 단일 ZIP으로 압축 보관 및 DB 아카이브 이력 생성 여부", example = "true")
        boolean archive,

        @Schema(description = "3단계: 원본 파일 디렉터리 삭제 및 DB 원본 데이터 정리 레코드 이관 실행 여부", example = "true")
        boolean cleanup
) {
}
