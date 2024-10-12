package com.sparta.notificationsystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Configuration
public class SinkConfig {
    @Bean
    public Sinks.Many<String> sink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    // 테스트를 위해 SSE 스트림을 제공하는 메서드
    @Bean
    public Flux<String> restockNotificationStream(Sinks.Many<String> sink) {
        return sink.asFlux();  // Sink를 통해 Flux 스트림 생성
    }

}
