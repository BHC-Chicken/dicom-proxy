package dev.ioexception.dicom.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;

@Getter
@RequiredArgsConstructor
public enum MetadataFormat {
    JSON("application/json", MediaType.APPLICATION_JSON),
    XML("multipart/related;type=application/dicom+xml", MediaType.APPLICATION_XML),
    HTML("multipart/related;type=text/html", MediaType.TEXT_HTML);

    private final String acceptHeader;
    private final MediaType mediaType;

    public static MetadataFormat from(String format) {
        if (format == null) {
            return JSON;
        }
        return switch (format.toLowerCase()) {
            case "xml" -> XML;
            case "html" -> HTML;
            default -> JSON;
        };
    }
}
