package dev.ioexception.dicom.config.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._helpers.bulk.BulkIngester;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {
	private final ElasticsearchProperties elasticsearchProperties;

	private final int FLUSH_INTERVAL = 1;
	private final int MAX_OPERATION = 100;
	private final int CONNECT_TIMEOUT = 5_000;
	private final int SOCKET_TIMEOUT = 60_000;

	@Bean
	public Rest5Client buildClient() throws Exception {
		List<String> uris = elasticsearchProperties.getUris();

		if (uris == null || uris.isEmpty()) {
			throw new IllegalStateException("Elasticsearch URIs가 설정되지 않았습니다.");
		}

		HttpHost[] httpHosts = new HttpHost[uris.size()];

		for (int i = 0; i < httpHosts.length; i++) {
			httpHosts[i] = HttpHost.create(uris.get(i));
		}

		// 2. RestClient -> Rest5Client 로 변경
		Rest5ClientBuilder restClientBuilder = Rest5Client.builder(httpHosts)
				.setDefaultHeaders(new BasicHeader[]{new BasicHeader("my-header", "my-value")})
				.setRequestConfigCallback(
						requestConfigBuilder -> requestConfigBuilder
								// 3. HC5 규격에 맞게 Timeout 객체 사용 및 이름 변경 반영
								.setResponseTimeout(Timeout.ofMilliseconds(SOCKET_TIMEOUT))
				);

		String caPath = elasticsearchProperties.getCaPath();
		CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		X509Certificate trustedCa;

		try (FileInputStream fis = new FileInputStream(caPath)) {
			trustedCa = (X509Certificate) certificateFactory.generateCertificate(fis);
		}

		KeyStore trustStore = KeyStore.getInstance("pkcs12");
		trustStore.load(null, null);
		trustStore.setCertificateEntry("ca", trustedCa);

		SSLContext sslContext = SSLContexts.custom()
				.loadTrustMaterial(trustStore, null)
				.build();

		TlsStrategy tlsStrategy = new DefaultClientTlsStrategy(sslContext);

		ConnectionConfig connectionConfig = ConnectionConfig.custom()
				.setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT))
				.build();

		@SuppressWarnings("resource")
		PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
				.setTlsStrategy(tlsStrategy)
				.setDefaultConnectionConfig(connectionConfig)
				.build();

		BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();

		credentialsProvider.setCredentials(
				new AuthScope(null, -1),
				new UsernamePasswordCredentials(elasticsearchProperties.getUsername(), elasticsearchProperties.getPassword().toCharArray())
		);

		restClientBuilder.setHttpClientConfigCallback(httpAsyncClientBuilder -> httpAsyncClientBuilder
				.setConnectionManager(connectionManager)
				.setDefaultCredentialsProvider(credentialsProvider)
		);

		return restClientBuilder.build();
	}

	@Bean
	public Rest5ClientTransport restClientTransport(Rest5Client restClient) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		// 6. RestClientTransport -> Rest5ClientTransport
		return new Rest5ClientTransport(restClient, new JacksonJsonpMapper(mapper));
	}

	@Bean
	public ElasticsearchClient elasticsearchClient(Rest5ClientTransport restClientTransport) {
		return new ElasticsearchClient(restClientTransport);
	}

	@Bean
	public ElasticsearchAsyncClient elasticsearchAsyncClient(Rest5ClientTransport restClientTransport) {
		return new ElasticsearchAsyncClient(restClientTransport);
	}

	@Bean
	public BulkIngester<BulkOperation> bulkIngester(ElasticsearchClient client, BulkIngestListenerImpl<BulkOperation> listener) {
		return BulkIngester.of(b -> b
				.client(client)
				.flushInterval(FLUSH_INTERVAL, TimeUnit.SECONDS)
				.maxOperations(MAX_OPERATION)
				.listener(listener));
	}

	@Bean
	public BulkIngestListenerImpl<BulkOperation> bulkIngestListener() {
		return new BulkIngestListenerImpl<>();
	}
}
