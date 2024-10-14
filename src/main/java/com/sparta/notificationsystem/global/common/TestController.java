package com.sparta.notificationsystem.global.common;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@Slf4j
@RequiredArgsConstructor
public class TestController {
    private final TestService testService;
    @GetMapping(value = "/products/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamNotifications() {
        return testService.getNotificationStream()
                .map(message -> ServerSentEvent.<String>builder()
                        .event("restock-notification")  // 이벤트 타입 설정
                        .data(message)  // 전송할 메시지 데이터
                        .build());
    }
}
