package com.sparta.notificationsystem.global.common;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class TestService {
    private final Flux<String> restockNotificationStream;

    public TestService(Flux<String> restockNotificationStream) {
        this.restockNotificationStream = restockNotificationStream;
    }

    // SSE 스트림을 제공하는 메서드
    public Flux<String> getNotificationStream() {
        // Sink에서 Flux로 변환하여 알림을 스트리밍
        return restockNotificationStream;
    }
}
