package dev.ioexception.dicom.config.dicom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import javax.net.ssl.*;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@Slf4j
@Configuration
public class DicomClientConfig {
    @Value("${base-url}")
    private String baseURL;

    @Bean
    public RestClient proxyRestClient(SslBundles sslBundles) throws Exception {
        SslBundle sslBundle = sslBundles.getBundle("dicom-bundle");

        SSLContext sslContext = createSslContext(sslBundle);
        HttpClient httpClient = createHttpClient(sslContext);

        return RestClient.builder()
                .baseUrl(baseURL)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Bean
    public DicomDcmClient dicomRestClient(RestClient proxyRestClient) {
        RestClientAdapter adapter = RestClientAdapter.create(proxyRestClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

        return factory.createClient(DicomDcmClient.class);
    }

    private SSLContext createSslContext(SslBundle sslBundle) throws Exception {
        X509ExtendedKeyManager forcedKm = createForcedKeyManager(sslBundle);
        TrustManager[] trustAllCerts = createTrustAllManagers();

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(new KeyManager[]{forcedKm}, trustAllCerts, new SecureRandom());

        return sslContext;
    }

    private X509ExtendedKeyManager createForcedKeyManager(SslBundle sslBundle) {
        X509ExtendedKeyManager originalKm = (X509ExtendedKeyManager) sslBundle.getManagers().getKeyManagers()[0];

        return new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[]{"ssl"}; }
            @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return "ssl"; }
            @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return "ssl"; }

            // 나머지 메서드는 원본에 위임
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return originalKm.getServerAliases(keyType, issuers); }
            @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return originalKm.chooseServerAlias(keyType, issuers, socket); }
            @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) { return originalKm.chooseEngineServerAlias(keyType, issuers, engine); }
            @Override public X509Certificate[] getCertificateChain(String alias) { return originalKm.getCertificateChain(alias); }
            @Override public PrivateKey getPrivateKey(String alias) { return originalKm.getPrivateKey(alias); }
        };
    }

    private TrustManager[] createTrustAllManagers() {
        return new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        };
    }

    private HttpClient createHttpClient(SSLContext sslContext) {
        SSLParameters sslParams = new SSLParameters();
        sslParams.setProtocols(new String[]{"TLSv1.2"});

        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(sslContext)
                .sslParameters(sslParams)
                .build();
    }
}
