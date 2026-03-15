package dev.ioexception.dicom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;

@Configuration
public class DicomClientConfig {
    @Value("${base-url}")
    private String baseURL;

    @Bean
    public DicomClient dicomRestClient(SslBundles sslBundles) {
        SslBundle sslBundle = sslBundles.getBundle("dicom-bundle");
        HttpClient client = HttpClient.newBuilder()
                .sslContext(sslBundle.createSslContext())
                .build();

        RestClient restClient = RestClient.builder()
                .baseUrl(baseURL)
                .requestFactory(new JdkClientHttpRequestFactory(client))
                .build();

        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

        return factory.createClient(DicomClient.class);
    }
}
