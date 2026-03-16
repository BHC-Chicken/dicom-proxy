package dev.ioexception.dicom.config;

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
import java.security.*;
import java.security.cert.X509Certificate;

@Slf4j
@Configuration
public class DicomClientConfig {
    @Value("${base-url}")
    private String baseURL;

    @Bean
    public DicomClient dicomRestClient(SslBundles sslBundles) throws NoSuchAlgorithmException, KeyManagementException {
        SslBundle sslBundle = sslBundles.getBundle("dicom-bundle");

        // 1. Spring이 로드한 얌전한 원본 KeyManager 가져오기
        X509ExtendedKeyManager originalKm = (X509ExtendedKeyManager) sslBundle.getManagers().getKeyManagers()[0];

        // 2. [핵심] curl처럼 묻지도 따지지도 않고 인증서를 무조건 던지는 막무가내 KeyManager 생성
        X509ExtendedKeyManager forcedKm = new X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                // 서버가 요구하는 발급자(issuers) 목록을 가볍게 무시하고 null로 처리
                return originalKm.getClientAliases(keyType, null);
            }
            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                String[] aliases = getClientAliases(keyType[0], null);
                return (aliases != null && aliases.length > 0) ? aliases[0] : null;
            }
            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                // 어떤 조건이든 무조건 내 지갑에 있는 첫 번째 인증서(alias)를 꺼내서 전송!
                String[] aliases = getClientAliases(keyType[0], null);
                return (aliases != null && aliases.length > 0) ? aliases[0] : null;
            }
            // 나머지 메서드는 원본에 위임
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return originalKm.getServerAliases(keyType, issuers); }
            @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return originalKm.chooseServerAlias(keyType, issuers, socket); }
            @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) { return originalKm.chooseEngineServerAlias(keyType, issuers, engine); }
            @Override public X509Certificate[] getCertificateChain(String alias) { return originalKm.getCertificateChain(alias); }
            @Override public PrivateKey getPrivateKey(String alias) { return originalKm.getPrivateKey(alias); }
        };

        // 3. 서버의 인증서를 무조건 신뢰하는 TrustManager (이전과 동일)
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        };

        // 4. 개조된 KeyManager와 TrustManager 장착!
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(new KeyManager[]{forcedKm}, trustAllCerts, new SecureRandom());

        HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();

        RestClient restClient = RestClient.builder()
                .baseUrl(baseURL)
                .requestFactory(new JdkClientHttpRequestFactory(client))
                .requestInterceptor(((request, body, execution) -> {
                    log.info("[DicomClient] 실제 요청 URL: {} {}", request.getMethod(), request.getURI());
                    return execution.execute(request, body);
                }))
                .build();

        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

        return factory.createClient(DicomClient.class);
    }
}
