package dev.ioexception.dicom.config.discord;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

public interface DiscordWebhookClient {
	@PostExchange(
			value = "", // 웹훅 URL이 전체 경로를 포함하므로 /
			contentType = "application/json"
	)
	void sendAlert(@RequestBody Map<String, Object> payload);
}
