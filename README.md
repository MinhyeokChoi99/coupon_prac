# Coupon Prac

Spring Boot 4.1.1 / Java 25 / MySQL 8.4 쿠폰 프로젝트.
최신 ERD의 7개 테이블로 적재·발급·QR·사용·조회 흐름을 구현한다.

- 적재: 캠페인별 날짜 이벤트와 UUID 재고를 준비한다.
- 발급: `FOR UPDATE SKIP LOCKED`로 한 행을 선점한다. 같은 이벤트 한 장, 한국 날짜 하루 3장.
- QR·사용: UUID 토큰 교체, 60초 만료, 사용 기록 0..1.
- 조회: 사용자 쿠폰 페이지와 상세·사용 내역.

[ERD·필드 설명·실제 SQL 흐름](docs/current-coupon-erd.md) · [부하테스트](docs/load-testing.md) · [모니터링](docs/local-development.md)

## 주의

V3는 승인된 연습 데이터 재생성용이다. 기존 `coupon_event`, `coupon_inventory`, `user_coupon`, `user_coupon_daily_limit`을 삭제하고 새 구조로 만든다.
**실데이터 DB에 적용하지 않는다.** V1/V2 파일은 기존 체크섬 유지용으로 남긴다.

API는 연습용이며 인증·인가는 아직 없다. 요청의 userId/ownerId와 주문 금액을 신뢰할 수 있는 운영 API로 사용하면 안 된다.
예약 작업 등록, 품절 이벤트 발행, Redis, 포인트 결제, QR 이미지 생성은 미구현이다.

## 실행

```sh
docker compose up -d
MANAGEMENT_ADDRESS=0.0.0.0 ./gradlew bootRun
```

테스트 데이터까지 만들려면:

```sh
COUPON_LOADTEST_RESET=true ./gradlew bootRun --args='--spring.profiles.active=loadtest'
```

loadtest 프로필은 30개 캠페인·이벤트, 15,000개 쿠폰, 50,000명 사용자를 만든다.
기존 앱을 종료한 뒤 실행한다. 기본 발급 기간은 준비 시점부터 10분이다.

## 테스트

Docker가 실행된 상태에서:

```sh
./gradlew test
```

별도 MySQL Testcontainers DB에서 마이그레이션, 동시 발급·중복·일일 한도,
한국 자정 경계, QR 교체·만료·중복 사용, 조회를 검증한다. 로컬 개발 DB를 지우지 않는다.
