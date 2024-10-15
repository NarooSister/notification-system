# 💡 재입고 알림 시스템

상품이 재입고 되었을 때, 알림을 설정한 유저에게 재입고 알림을 보내주는 시스템입니다.

WebFlux를 사용해 SSE로 알림을 구현하고, Redis에 데이터를 캐싱하여 응답 시간을 줄였습니다.



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
![스크린샷 2024-10-12 115527](https://github.com/user-attachments/assets/89fade0d-6fd6-4e2d-a309-a51d762a8e95)



## 3. 기능 구현

### 1️⃣ 재입고 알림 전송 API
- POST /products/{productId}/notifications/re-stock

<br>

**1. 재입고 알림 테스트 성공**

![스크린샷 2024-10-15 135119](https://github.com/user-attachments/assets/16291aca-38db-4384-9fb7-6ef49e3d6e31)

**2. html 페이지에 호출 테스트 성공**

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
  - JPA의 블로킹 방식을 고려하여 스케줄러를 사용해 JPA 작업을 별도의 스레드에서 처리하도록 구현하였습니다.
    
    
### ✅ 성능 최적화를 위한 비동기와 Redis 사용


