package com.sparta.notificationsystem.service;

import com.sparta.notificationsystem.entity.Product;
import com.sparta.notificationsystem.entity.ProductUserNotification;
import com.sparta.notificationsystem.repository.ProductNotificationHistoryRepository;
import com.sparta.notificationsystem.repository.ProductRepository;
import com.sparta.notificationsystem.repository.ProductUserNotificationHistoryRepository;
import com.sparta.notificationsystem.repository.ProductUserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.Mockito.*;

public class ServiceUnitTest {
    @InjectMocks
    private ProductNotificationService productNotificationService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductUserNotificationRepository productUserNotificationRepository;

    @Mock
    private ProductNotificationHistoryRepository productNotificationHistoryRepository;

    @Mock
    private ProductUserNotificationHistoryRepository productUserNotificationHistoryRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("상품을 찾지 못했을 때 NoSuchElementException을 발생시킴")
    void process_ProductNotFound_ThrowsException() {
        // given
        Long productId = 1L;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:" + productId)).thenReturn(null); // Redis에 없음
        when(productRepository.findById(productId)).thenReturn(Optional.empty());   //DB에도 없음

        // When
        Mono<Boolean> result = productNotificationService.processRestockNotification(1L);

        // Then
        // 비동기적 코드에서 예외발생을 테스트할 때는 StepVerifier 사용
        StepVerifier.create(result)
                .expectError(NoSuchElementException.class)
                .verify();
    }

    @Test
    @DisplayName("재고가 없는 경우 예외가 발생하는지")
    void process_OutOfStock_ThrowsException() {
        // Given
        Long productId = 1L;
        Product testProduct = new Product(productId, 0, "상품", 0);

        // RedisTemplate에서 재고가 0으로 설정된 경우
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:" + productId)).thenReturn(testProduct);
        when(valueOperations.get("productStock:" + productId)).thenReturn(0);  // 재고가 없는 경우

        // When
        Mono<Boolean> result = productNotificationService.processRestockNotification(productId);

        // Then
        StepVerifier.create(result)
                .expectError(NoSuchElementException.class)  // 재고 부족 예외 발생 여부 확인
                .verify();
    }

    @Test
    @DisplayName("알림을 설정한 유저가 없는 경우 예외가 발생하는지")
    void process_NoUsers_ThrowsException() {
        // Given
        Long productId = 1L;
        Product testProduct = new Product(productId, 0, "상품", 10);

        // RedisTemplate에서 상품 및 재고 설정
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:" + productId)).thenReturn(testProduct);
        when(valueOperations.get("productStock:" + productId)).thenReturn(testProduct.getStock());

        // 알림을 설정한 유저가 없는 상황 모킹 (빈 리스트 반환)
        when(productUserNotificationRepository.findByProductId(productId)).thenReturn(List.of());

        // When
        Mono<Boolean> result = productNotificationService.processRestockNotification(productId);

        // Then
        StepVerifier.create(result)
                .expectError(NoSuchElementException.class)  // 예외 발생 확인
                .verify();

    }

    @Test
    @DisplayName("알림 전송 중 재고가 0이 되었을 때 예외 발생 테스트")
    void process_StockBecomesZeroDuringNotification_ThrowsException() {
        // Given
        Long productId = 1L;
        Product testProduct = new Product(productId, 0, "상품", 10);

        // Sinks 초기화 (SSE 용)
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        ReflectionTestUtils.setField(productNotificationService, "sink", sink);

        // Redis 및 DB 설정
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:" + productId)).thenReturn(testProduct);
        when(valueOperations.get("productStock:" + productId))
                .thenReturn(testProduct.getStock())  // 첫 번째 호출에서는 재고 10
                .thenReturn(0);  // 두 번째 호출에서는 재고 0

        // 유저 알림 설정
        List<ProductUserNotification> notifications = List.of(
                new ProductUserNotification(productId, 1L),
                new ProductUserNotification(productId, 2L)
        );
        when(productUserNotificationRepository.findByProductId(productId)).thenReturn(notifications);

        // When
        Mono<Boolean> result = productNotificationService.processRestockNotification(productId);

        // Then
        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)  // 예외 발생을 기대
                .verify();

        verify(valueOperations, times(2)).get("productStock:" + productId);  // 두 번 호출됨을 확인
    }

    @Test
    @DisplayName("알림이 모두 전송이 잘 된 경우")
    void process_NotificationsSentSuccessfully_ReturnsTrue() {
        // Given
        Long productId = 1L;
        Product product = new Product(productId, 1, "상품", 10); // 재고가 10인 상품
        List<ProductUserNotification> notifications = List.of(
                new ProductUserNotification(productId, 1L),
                new ProductUserNotification(productId, 2L)
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 캐시에서 Product와 stock 정보를 반환
        when(valueOperations.get("product:" + productId)).thenReturn(product);
        when(valueOperations.get("productStock:" + productId)).thenReturn(product.getStock());

        // 알림 유저 목록 DB에서 조회
        when(productUserNotificationRepository.findByProductId(productId)).thenReturn(notifications);

        // Sinks.Many 객체를 초기화
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        ProductNotificationService notificationService = new ProductNotificationService(
                sink,
                sink.asFlux(),  // restockNotificationStream에 대한 플럭스 스트림 설정
                productRepository,
                productUserNotificationRepository,
                productUserNotificationHistoryRepository,
                productNotificationHistoryRepository,
                redisTemplate
        );

        // When
        StepVerifier.create(notificationService.processRestockNotification(productId))
                // Then
                .expectNext(true)  // 알림이 성공적으로 전송되었을 때 true를 반환하는지 확인
                .verifyComplete();

        // Verify that save method was called for product increment and user notifications
        verify(productRepository, times(1)).save(product);  // 재입고 회차 증가를 위해 save 호출 확인
        verify(productUserNotificationRepository, times(1)).findByProductId(productId);
        verify(redisTemplate.opsForValue(), times(1)).get("product:" + productId);
        verify(redisTemplate.opsForValue(), times(3)).get("productStock:" + productId);
    }
    @Test
    @DisplayName("Redis 캐시 미스가 발생했을 때 DB에서 데이터를 가져오고 다시 캐시에 저장되는지 테스트")
    void process_RedisCacheMiss_FetchFromDBAndCacheIt() {
        // given

        // when

        // then
    }
}
