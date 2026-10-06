package dev.ioexception.dicom.config.googleai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "google.ai")
public class GoogleAiProperties {
    private String apiKey;
    private String embeddingModel = "gemini-embedding-2";
    private Integer outputDimensionality = 768;
    private String chatModel = "gemini-1.5-flash";
}
