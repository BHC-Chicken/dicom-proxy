#!/usr/bin/env python3
import os
import sys
import subprocess
from collections import defaultdict

# 1. pydicom 패키지 자동 체크 및 설치
try:
    import pydicom
except ImportError:
    print("pydicom 라이브러리가 존재하지 않아 자동으로 설치합니다...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "pydicom"])
    import pydicom

BOUNDARY = "----WebKitFormBoundaryDicomProxySTOWRS"

def pack_dcm_files_by_study(source_dir, output_dir):
    os.makedirs(output_dir, exist_ok=True)
    study_map = defaultdict(list)

    print(f"🔍 [스캔 시작] 디렉토리: {source_dir}")

    # 2. 디렉토리 내 모든 DICOM 파일 스캔 및 StudyInstanceUID 그룹핑
    scanned_count = 0
    for root, _, files in os.walk(source_dir):
        for filename in files:
            filepath = os.path.join(root, filename)
            # .dcm 확장자 또는 확장자가 없는 파일도 체크 시도
            try:
                ds = pydicom.dcmread(filepath, stop_before_pixels=True, force=True)
                study_uid = getattr(ds, "StudyInstanceUID", None)
                if study_uid:
                    study_map[str(study_uid)].append(filepath)
                    scanned_count += 1
            except Exception:
                continue

    print(f"✅ 총 {scanned_count} 개 인스턴스 스캔 완료 -> {len(study_map)} 개의 고유 StudyUID 그룹 감지됨\n")

    # 3. StudyUID 별로 분리하여 각각 .dat 멀티파트 패키지 파일 생성
    for index, (study_uid, file_paths) in enumerate(study_map.items(), 1):
        output_dat_path = os.path.join(output_dir, f"study_{study_uid}.dat")
        print(f"[{index}/{len(study_map)}] .dat 포장 중: study_{study_uid}.dat (인스턴스 {len(file_paths)}개)")

        with open(output_dat_path, "wb") as out_f:
            for filepath in file_paths:
                with open(filepath, "rb") as in_f:
                    file_bytes = in_f.read()

                header = (
                    f"--{BOUNDARY}\r\n"
                    f"Content-Type: application/dicom\r\n"
                    f"Content-Length: {len(file_bytes)}\r\n\r\n"
                ).encode("utf-8")

                out_f.write(header)
                out_f.write(file_bytes)
                out_f.write(b"\r\n")

            closing = f"--{BOUNDARY}--\r\n".encode("utf-8")
            out_f.write(closing)

        size_mb = os.path.getsize(output_dat_path) / (1024 * 1024)
        print(f"  └─ 📦 생성 완료: {output_dat_path} ({size_mb:.2f} MB)")

    print(f"\n🎉 모든 Study 별 .dat 파일이 {output_dir} 에 성공적으로 저장되었습니다!")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("사용법: python3 organize_and_pack_dat.py <DICOM파일_디렉토리> [출력_디렉토리]")
        print("예시: python3 organize_and_pack_dat.py /path/to/dicom_folder ./output_dats")
        sys.exit(1)

    src_dir = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "./output_dats"
    pack_dcm_files_by_study(src_dir, out_dir)
