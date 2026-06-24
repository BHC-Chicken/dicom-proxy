package dev.ioexception.dicom.config.discord;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class DiscordClientConfig {

	@Bean
	public DiscordWebhookClient discordWebhookClient() {
		RestClient restClient = RestClient.builder()
				.baseUrl("https://discordapp.com/api/webhooks/1504009932687675392/qnowT2co3No8Usonpx-WsUmTX5A6WX6HQRPv8TQT2HV_IqereTdDh6Pop0ckO1E6G3cu")
				.build();
		RestClientAdapter adapter = RestClientAdapter.create(restClient);
		HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

		return factory.createClient(DiscordWebhookClient.class);
	}
}
