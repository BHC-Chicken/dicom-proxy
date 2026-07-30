package dev.ioexception.dicom.common;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class DicomDatPackerUtil {

    public static final String DEFAULT_BOUNDARY = "----WebKitFormBoundaryDicomProxySTOWRS";

    /**
     * DICOM 파일 목록을 STOW-RS (multipart/related) 패키지 구조의 단일 .dat 파일로 생성합니다.
     */
    public static void packDicomFilesToDatFile(List<File> dicomFiles, File outputDatFile, String boundary) throws IOException {
        if (boundary == null || boundary.isBlank()) {
            boundary = DEFAULT_BOUNDARY;
        }

        log.info("[DicomDatPackerUtil] DICOM 파일 {}개를 .dat 파일로 패키징 시작 -> {}", dicomFiles.size(), outputDatFile.getAbsolutePath());

        try (FileOutputStream fos = new FileOutputStream(outputDatFile)) {
            for (File file : dicomFiles) {
                writePartHeader(fos, boundary, file.length());

                try (FileInputStream fis = new FileInputStream(file)) {
                    fis.transferTo(fos);
                }

                fos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }

            writeClosingBoundary(fos, boundary);
        }

        log.info("[DicomDatPackerUtil] .dat 파일 생성 완료: {} (크기: {} bytes)", outputDatFile.getName(), outputDatFile.length());
    }

    /**
     * 바이트 배열 DICOM 파일 목록을 STOW-RS (.dat) 바이트 배열로 생성합니다.
     */
    public static byte[] packDicomBytesToDatBytes(List<byte[]> dicomBytesList, String boundary) throws IOException {
        if (boundary == null || boundary.isBlank()) {
            boundary = DEFAULT_BOUNDARY;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (byte[] dicomBytes : dicomBytesList) {
            writePartHeader(baos, boundary, dicomBytes.length);
            baos.write(dicomBytes);
            baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        writeClosingBoundary(baos, boundary);
        return baos.toByteArray();
    }

    /**
     * DICOM 파일 목록을 Zero-Copy 스트리밍이 가능한 단일 InputStream으로 결합합니다.
     */
    public static InputStream packDicomFilesToDatStream(List<File> dicomFiles, String boundary) throws IOException {
        if (boundary == null || boundary.isBlank()) {
            boundary = DEFAULT_BOUNDARY;
        }

        InputStream combined = new ByteArrayInputStream(new byte[0]);

        for (File file : dicomFiles) {
            String header = "--" + boundary + "\r\n" +
                    "Content-Type: application/dicom\r\n" +
                    "Content-Length: " + file.length() + "\r\n\r\n";
            ByteArrayInputStream headerStream = new ByteArrayInputStream(header.getBytes(StandardCharsets.UTF_8));
            FileInputStream fileStream = new FileInputStream(file);
            ByteArrayInputStream trailerStream = new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.UTF_8));

            combined = new SequenceInputStream(combined, headerStream);
            combined = new SequenceInputStream(combined, fileStream);
            combined = new SequenceInputStream(combined, trailerStream);
        }

        String closingBoundary = "--" + boundary + "--\r\n";
        ByteArrayInputStream closingStream = new ByteArrayInputStream(closingBoundary.getBytes(StandardCharsets.UTF_8));
        return new SequenceInputStream(combined, closingStream);
    }

    private static void writePartHeader(FileOutputStream fos, String boundary, long contentLength) throws IOException {
        String header = "--" + boundary + "\r\n" +
                "Content-Type: application/dicom\r\n" +
                "Content-Length: " + contentLength + "\r\n\r\n";
        fos.write(header.getBytes(StandardCharsets.UTF_8));
    }

    private static void writePartHeader(ByteArrayOutputStream baos, String boundary, long contentLength) throws IOException {
        String header = "--" + boundary + "\r\n" +
                "Content-Type: application/dicom\r\n" +
                "Content-Length: " + contentLength + "\r\n\r\n";
        baos.write(header.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeClosingBoundary(FileOutputStream fos, String boundary) throws IOException {
        String trailer = "--" + boundary + "--\r\n";
        fos.write(trailer.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeClosingBoundary(ByteArrayOutputStream baos, String boundary) throws IOException {
        String trailer = "--" + boundary + "--\r\n";
        baos.write(trailer.getBytes(StandardCharsets.UTF_8));
    }
}
