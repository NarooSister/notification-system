package com.sparta.notificationsystem.controller;

import com.sparta.notificationsystem.notification.productnotification.controller.ProductNotificationController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(ProductNotificationController.class)
class ProductNotificationControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("1초에 500개의 재입고 알림 API 요청 처리 테스트")
    void test500ApiRequestsPerSecond() {
        Long productId = 1L;


    }

}
