package dev.ioexception.dicom.config.elasticsearch;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class ElasticsearchProperties {
	private List<String> uris;
	private String username;
	private String password;
	private String caPath;
}
