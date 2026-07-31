package dev.ioexception.dicom.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void asyncProcessingIsEnabledAndDiscordAlertsUseNonThrowingRejectionPolicy() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            assertThat(AsyncConfig.class.isAnnotationPresent(EnableAsync.class)).isTrue();
            ThreadPoolTaskExecutor executor = context.getBean(
                    "discordAlertExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.DiscardPolicy.class);
        }
    }
}
