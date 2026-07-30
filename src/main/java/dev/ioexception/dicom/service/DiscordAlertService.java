package dev.ioexception.dicom.service;

import dev.ioexception.dicom.config.discord.DiscordWebhookClient;
import dev.ioexception.dicom.event.DicomErrorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordAlertService {
	private final DiscordWebhookClient discordWebhookClient;

	@Async("discordAlertExecutor")
	@EventListener
	public void handleDicomErrorEvent(DicomErrorEvent event) {
		String errorMessage = event.exception() != null ? event.exception().getMessage() : "알 수 없는 에러";
		log.info("디스코드 비동기 예외 알림 수신 - Trace ID: {}, Error: {}", event.traceId(), errorMessage);

		try {
			Map<String, Object> embed = Map.of(
					"title", "🚨 시스템 에러 발생",
					"color", 16711680,
					"description", String.format("**에러:** %s\n**Trace ID:** `%s`", errorMessage, event.traceId())
			);

			Map<String, Object> payload = Map.of("embeds", new Object[]{embed});

			discordWebhookClient.sendAlert(payload);
		} catch (Exception e) {
			log.error("디스코드 알림 전송 중 예외 발생", e);
		}
	}
}
