package dev.ioexception.dicom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class DicomApplicationTests {

    @Test
    void applicationEntryPointIsConfigured() {
        if (!DicomApplication.class.isAnnotationPresent(SpringBootApplication.class)) {
            throw new AssertionError("DicomApplication must be annotated with @SpringBootApplication");
        }
    }

}
