package dev.ioexception.dicom.common;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

@Slf4j
public class RecordingInputStream extends FilterInputStream {
    private final ByteArrayOutputStream recordedBuffer = new ByteArrayOutputStream();

    public RecordingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            recordedBuffer.write(b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            recordedBuffer.write(b, off, n);
        }
        return n;
    }

    public InputStream toRecombinedStream() {
        byte[] recordedBytes = recordedBuffer.toByteArray();
        log.debug("[RecordingInputStream] Header Peek 스캔 완료 - 캡처된 헤더 크기: {} bytes", recordedBytes.length);
        ByteArrayInputStream recordedInput = new ByteArrayInputStream(recordedBytes);
        return new SequenceInputStream(recordedInput, this.in);
    }

    public int getRecordedSize() {
        return recordedBuffer.size();
    }
}
