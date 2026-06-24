package dev.ioexception.dicom.config.discord;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class DiscordClientConfig {
	@Value("${DISCORD_WEBHOOK_URL}")
	private String discordWebhookURL;


	@Bean
	public DiscordWebhookClient discordWebhookClient() {
		RestClient restClient = RestClient.builder()
				.baseUrl(discordWebhookURL)
				.build();
		RestClientAdapter adapter = RestClientAdapter.create(restClient);
		HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

		return factory.createClient(DiscordWebhookClient.class);
	}
}
