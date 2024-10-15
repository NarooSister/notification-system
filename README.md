# 💡 재입고 알림 시스템

상품이 재입고 되었을 때, 알림을 설정한 유저에게 재입고 알림을 보내주는 시스템입니다.

WebFlux를 사용해 SSE로 알림을 구현하고, Redis에 데이터를 캐싱하여 응답 시간을 줄였습니다.

<br>

## 프로젝트 소개

기간: 2024.10.12~ 2024.10.15

## 1. 개발 환경
- Back-end : Java 17, Spring Boot 3.3.5, Spring Data JPA
- Database : MySQL 9.0, Redis
- infra : docker

## 2. 주요 기능

- 상품의 재입고 회차가 1 증가했을 때, 재입고 알림의 전송 API가 요청된다고 가정합니다.
- 재입고 알림을 보내던 중 재고가 없어진다면 CANCELED_BY_SOLD_OUT (품절에 의한 발송 중단)이 일어납니다.
  - 재고가 없어지는 상황은 stock<=0 인 상황으로 가정하고 테스트코드로 확인합니다. 
- 예외에 의해 알림 메시지 발송이 실패한 경우 manual한 재입고 알림 API를 호출하여 마지막으로 전송 성공한 이후의 유저부터 다시 알림 메시지를 보낼 수 있습니다.
- 알림 메시지는 1초에 최대 500개의 요청을 보낼 수 있습니다.
- ProducUserNotification 테이블은 재입고 알림을 설정한 유저 목록이며, 알림이 요청되면 유저들에게 알림 메시지를 전달합니다.
- 회차별 재입고 알림을 받은 유저 목록을 ProductUserNotificationHistory 테이블에 저장합니다.

### ERD

![image](https://github.com/user-attachments/assets/4a0eaf64-7445-4826-a961-591f1fb9155d)



## 3. 기능 구현

### 1️⃣ 재입고 알림 전송 API
- POST /products/{productId}/notifications/re-stock

<br>

**1. 재입고 알림 테스트 성공**

![스크린샷 2024-10-15 135119](https://github.com/user-attachments/assets/16291aca-38db-4384-9fb7-6ef49e3d6e31)

**2. html 페이지에 호출 테스트 성공(SSE 방식)**

![스크린샷 2024-10-15 140055](https://github.com/user-attachments/assets/fd5ed65c-b4e9-4dfc-8dbe-64e478c5a0bd)

**3. 테스트코드 성공 (단위 테스트, 통합 테스트 작성)**

![스크린샷 2024-10-15 034251](https://github.com/user-attachments/assets/aea13bdf-bfd4-4857-96a5-4c00cfa1555d)


### 2️⃣ 재입고 알림 전송 API (manual)
- POST /admin/products/{productId}/notifications/re-stock

<br>

**1. 알림 재전송 성공**

![image](https://github.com/user-attachments/assets/615fce13-0a25-4d8e-af05-64f16530e568)
  
**2. 취소된 알림이 없는 경우에는 실패**

![스크린샷 2024-10-15 140125](https://github.com/user-attachments/assets/696cdf89-e89a-4af7-9252-adb49058d1bf)

**3. 두 번째 유저까지 알림을 전송한 뒤 Error에 의해 취소된 경우 DB에 저장되는 History**

- 상태(CANCELED_BY_ERROR)와 마지막 전송 유저 id가 저장된다.
![스크린샷 2024-10-15 153011](https://github.com/user-attachments/assets/6980f94e-704e-4ca2-8681-6d333cd34aa4)

- 매뉴얼로 재전송하면 그 이후의 id부터 저장된다.
![스크린샷 2024-10-15 152911](https://github.com/user-attachments/assets/74a5d60f-2e29-42f6-aa37-df4d6b4018a8)


<br>

## 4. 문제 해결 과정 
### ✅ 알림 프로세스의 로직 설정
**1. 요구사항 분석**
- 이 서비스는 제품의 재입고 시 사용자에게 실시간 알림을 보내고, 알림을 기록합니다.
- 1초에 최대 500개의 요청을 받을 수 있으며 재고가 0이 되면 알림 전송이 중단됩니다.
- 알림 전송이 중단되면 매뉴얼 전송 API를 통해 다음 유저부터 순차적으로 알림을 전송할 수 있습니다.

<br>

**2. 기술 선택 이유**

**Redis :**

  - 재고가 0이 되는 순간을 캐치하려면 매번 재고 정보를 가져와야하기 때문에 Redis를 사용해 재고 정보, Product, userId 등을 캐싱해두고 관리했습니다.
    
**SSE 방식의 알림 :**

  - 실시간 알림 전송을 위해 사용했습니다.
  - 양방향 통신이 필요하지 않고, 빠른 전송이 필요했기 때문에 SSE 방식으로 구현했습니다.
    
**WebFlux :**

  - SSE 구현과 비동기 처리를 위해 사용했습니다.
  - WebFlux의 반응형 스트림을 통해 알림 기능을 구현했고, 1초에 500개의 요청을 생각하여 비동기 처리를 했습니다.
    
**JPA와 Scheduler :**

  - WebFlux 사용을 고려하면서 R2DBC를 사용해야하나 고민했지만, 짧은 시간과 러닝커브로 인해 JPA 사용을 선택했습니다.
  - JPA의 블로킹 방식을 고려하여 스케줄러를 사용해 JPA 작업을 별도의 스레드에서 처리했습니다.

**3. 알림 프로세스 로직**

- **프로세스 시작** : 상품이 재입고될 때, 재고 상태와 상품 정보를 확인.
  
- **상품 및 재고 확인** : Redis에서 제품 정보를 조회하고, 유효성을 검사. 재고가 0이면 예외가 발생.
  
- **알림 설정한 사용자 목록 조회**: Redis에서 사용자 Id 목록을 가져옴. 없다면 DB에서 조회하여 Redis에 캐싱.
  
- **알림 전송**: 알림 설정을 한 모든 사용자에게 알림을 전송하고 기록. 비동기적으로 처리.

- **알림 기록 저장**: 알림을 전송한 후, 알림 기록을 저장.
  
- **에러 처리**: 알림 전송 중 오류가 발생할 경우, 오류를 로깅하고 에러 처리를 수행. 마지막으로 성공적으로 알림을 보낸 사용자 Id 기록.

- **매뉴얼로 알림을 보내는 프로세스의 경우** : 다른 로직은 비슷하지만, 프로세스가 시작할 때 이전 알림 전송 기록을 확인한 뒤에 그보다 뒷번호의 유저부터 알림 전송

<br>  

### ✅ resilience4j를 이용한 RateLimiter 적용
- 1초의 최대 500개의 요청이라는 limit가 걸려있었기 때문에 resilience4j를 사용해서 rateLimiter를 적용했다.
- resilience4j는 WebFlux를 사용하는 비동기 호출과 함께 쓸 때 좋고 설정이 간단해서 선택했다.
- Bucket4j도 생각했으나 다음 프로젝트에서 resilience4j를 사용할 것이기 때문에 공부하면서 적용하기로 했다.
- fallbackMethod 대신에 GlobalExceptionHandler로 에러를 받았다.
  
```java
 @PostMapping("/products/{productId}/notifications/re-stock")
    @RateLimiter(name = "default")
    public Mono<ResponseEntity<String>> postNotifications(@PathVariable("productId") Long productId) {
        return productNotificationService.processRestockNotification(productId)
                .subscribeOn(Schedulers.boundedElastic())  // JPA 블로킹 작업을 비동기적으로 처리
                .map(success -> ResponseEntity.ok("재입고 알림이 성공적으로 전송되었습니다."));
    }
```
```java
  @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<RestApiException> handleRateLimitException(HttpServletRequest request) {
        RestApiException restApiException = new RestApiException(
                LocalDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Rate limit가 초과되었습니다. 나중에 다시 시도해 주세요.",
                request.getRequestURI()
        );
        return new ResponseEntity<>(restApiException, HttpStatus.TOO_MANY_REQUESTS);
    }
```

- yml 설정
  
```yml
resilience4j:
  ratelimiter:
    instances:
      default:
        limitForPeriod: 500  # 초당 허용되는 요청 수
        limitRefreshPeriod: 1s  # 1초마다 제한 초기화
        timeoutDuration: 0  # 제한을 넘었을 때 대기 시간 없음 (즉시 오류 반환)
```

<br>

### ✅ WebFlux를 사용한 SSE 방식으로 알림 구현
- 자세히 보니 요구사항이 아니었지만 제목이 알림 시스템이어서 당연히 구현해야 하는 줄 알고 구현했다.
- 웹소켓까지 사용하지 않아도 충분히 구현이 가능하기 때문에 SSE 방식으로 단방향 실시간 알림 시스템을 만들었다. 
- 알림 전송 과정은 Flux와 Sinks를 사용해서 구현했다.
- 클라이언트가 엔드포인트에 접근하면 서버에서 실시간으로 알림 메시지를 전송한다.
- 페이지를 하나 만들어서 javaScript로 EventSource를 사용해 알림을 테스트했다.
  
```java
@Configuration
public class SinkConfig {
    @Bean
    public Sinks.Many<String> sink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    @Bean
    public Flux<String> restockNotificationStream(Sinks.Many<String> sink) {
        return sink.asFlux();  // Sink를 통해 Flux 스트림 생성해서 알림 전송
    }
}
```

```java
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
```

```java
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
```
<br>



  
### ✅ 성능 최적화를 위한 비동기와 Redis 사용
**1. 최대 500개의 요청을 처리하기 위한 비동기 처리 도입**
- WebFlux를 활용하여 비동기 방식으로 재입고 알림 프로세스 관리

```java
@Transactional
public Mono<Boolean> processRestockNotification(Long productId) {
    return fetchProductAndStock(productId)        // 1. Product 및 stock 상태 확인
            .flatMap(this::notifyUsersAndHandleStock)  // 2. 알림 전송 및 재고 상태 처리
            .subscribeOn(Schedulers.boundedElastic())  // 3. 비동기 실행
            .onErrorResume(throwable -> handleProcessError(productId, throwable));  // 4. 오류 처리
    }
```

**2. 성능을 위해 병렬 처리 시도 및 실패**
- 처음에 요구사항을 완벽히 파악하지 못하고 병렬로 처리하려다가 재고를 정확히 파악할 수 없는 문제점을 알게 되었다.
- 여러 요청을 병렬로 처리하면서 재고가 0이 되는 순간 정확한 재고 정보를 가져오지 못했다.
- 요청을 순차적으로 처리하는 방식으로 바꾼 뒤 Redis에 데이터를 캐싱하여 정보를 빠르게 가져오도록 구현했다.

**3. Redis를 활용한 데이터 캐싱**
- Redis를 캐시로 사용하여 재고 정보, Product 객체, lastUserId를 저장하여 조회
- List<Long> 으로 유저 id를 저장하면서 String으로 변환시켜 사용했고, 파싱해서 가져왔다.

```java
private Integer getStockFromCacheOrProduct(Product product) {
    Integer stock = (Integer) redisTemplate.opsForValue().get("productStock:" + product.getId());

    if (stock == null) {
        // 캐시에 재고가 없으면 DB에서 조회하고 Redis에 저장
        stock = product.getStock();
        redisTemplate.opsForValue().set("productStock:" + product.getId(), stock);
    }

    return stock;
}
```

**4. Redis에 저장된 사용자 목록을 비동기적으로 알림 전송**
- 사용자 아이디 목록을 Flux로 받아 각 사용자에 대해 비동기 작업을 수행한다.
- concatMap을 통해 비동기 작업을 순차적으로 실행하고 저장한다.
```java
private Flux<Long> notifyUsers(NotificationContext context) {
    return Flux.fromIterable(context.userIds())  // 1. 사용자 ID 목록을 Flux로 변환
            .concatMap(userId -> getStockAndNotifyUser(context, userId)  // 2. 각 사용자에 대해 재고 확인 및 알림 전송
                    .flatMap(unused -> saveUserNotificationHistory(context, userId))  // 3. 알림 기록 저장
                    .thenReturn(userId));  // 4. 사용자 ID 반환
}
```

**5. 문제점 및 느낀점**
- 성능을 위해 비동기와 Redis를 도입했지만 1초당 500개까지의 요청을 처리하지는 못한다.
- 잘 모르는 Webflux를 사용하면서 코드가 복잡해졌고, 여기저기서 배운 내용으로 고쳐나가면서 하나의 메서드가 너무 길어졌다. 마지막에 메서드를 분리해서 리팩토링 했더니 가독성이 떨어졌다.
- JPA와 WebFlux를 사용하면서 트랜잭션이 제대로 이루어지는지 확인하기 어려웠다. 테스트 코드로 구현에 필요한 로직은 검증했지만, 어느 정도까지 트랜잭션 관리가 되고 있는지 모르겠다.
- 적당히 기능을 구현하고 테스트 코드를 작성하면서 예외를 많이 찾을 수 있었다.

