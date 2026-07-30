package dev.ioexception.dicom.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DicomXmlParserUtilTest {

    @Test
    void externalEntitiesAreNotExpanded() {
        String maliciousXml = """
                <?xml version="1.0"?>
                <!DOCTYPE NativeDicomModel [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <NativeDicomModel>
                  <DicomAttribute keyword="SOPInstanceUID"><Value>&xxe;</Value></DicomAttribute>
                </NativeDicomModel>
                """;

        String result = DicomXmlParserUtil.extractSuccessInfo(maliciousXml);

        assertThat(result).startsWith("원시 응답:");
        assertThat(result).doesNotContain("root:x:");
    }
}
