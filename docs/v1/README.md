# v1 쿠폰 구현 안내

v1은 **MySQL에 쿠폰을 한 장씩 미리 적재하고, 발급 요청이 재고 한 행을 잠가 가져가는 구현**이다.
`remaining_quantity` 같은 공용 재고 카운터나 Redis는 사용하지 않는다.
현재 코드 기준 문서이며, 전체 필드와 단계별 SQL은 [ERD·SQL 흐름](../current-coupon-erd.md)에 정리했다.

## 1. 패키지와 클래스 역할

기준 경로: `src/main/java/io/github/minhyeok/coupon_prac/`

```text
v1/
  CouponV1Application.java
  coupon/
    controller/CouponController.java
    service/CouponService.java
    dto/                              # API 요청·응답과 내부 발급 명령
    entity/                           # 7개 테이블 엔티티와 상태 enum
    repository/                       # 테이블별 JPA 조회·잠금·갱신
    exception/                        # 에러 코드·예외·v1 전용 예외 응답
```

- Controller: 입력값을 검증하고 서비스에 전달한다. SQL이나 할인 계산을 수행하지 않는다.
- `CouponService`: 적재·발급·QR·사용·조회 흐름을 하나의 서비스에 모은다.
- Entity: 상태와 데이터를 보관하고, 발급 시간·사용 가능 여부·QR 교체 같은 해당 행의 규칙을 검사한다.
- Repository: DB 조회와 잠금, 조건부 UPDATE를 담당한다. 테이블별로 유지한다.
- DTO: 엔티티를 그대로 노출하지 않고 API에 필요한 정보만 전달한다. QR은 QR 발급 응답에만 포함된다.
- 예외 처리: `v1.coupon` Controller에만 적용한다. 향후 v2의 예외 정책과 섞이지 않는다.

기존 `campaign`, `couponevent`, `couponinventory`, `couponusage`, `usercoupon`, `user`, `common` 패키지를
`v1/coupon`의 계층별 패키지로 통합했다. 현재 작은 연습 구현에서는 테이블마다 서비스를 만들지 않는다.
사용되지 않는 시간 유틸·Clock 설정·스케줄러·서버 부하테스트 패키지는 두지 않는다.

`CouponV1Application`은 v1 아래만 스캔한다. v2를 추가할 때는 v2의 앱 진입점과 빌드 실행 대상을 명시해야 한다.
v2 코드는 아직 없으며, `docs/v14-*`, `docs/v21-*`는 별도 도메인 설계안이지 이 구현의 버전 설명이 아니다.

## 2. 테이블은 무엇을 저장하는가?

| 테이블 / 엔티티 | 저장하는 내용 | 중요한 규칙 |
| --- | --- | --- |
| campaign / Campaign | 가게·점주·할인 조건·매일 적용할 시간·기간 | ACTIVE 캠페인만 신규 발급 허용 |
| coupon_event / CouponEvent | 캠페인의 특정 날짜 행사·수량·발급/사용 시각 | 캠페인·영업일 조합은 하나 |
| coupon_inventory / CouponInventory | 아직 배정되지 않았거나 발급된 쿠폰 한 장 | 이벤트·순번 및 쿠폰 코드 유일 |
| user / CouponUser | 사용자 외부 식별자·활성 상태 | 활성 사용자에게만 신규 발급 |
| coupon_daily_limit / UserCouponDailyLimit | 사용자별 한국 날짜 발급 횟수 | 사용자·날짜 유일, 하루 최대 3장 |
| user_coupon / UserCoupon | 특정 사용자에게 발급된 쿠폰·QR·사용 기간 | 같은 이벤트에서 사용자당 한 장 |
| coupon_usage_history / CouponUsageHistory | 쿠폰 사용 시각·실제 할인액 | user_coupon_id 유일: 쿠폰 1 : 기록 0..1 |

ID는 `BIGINT AUTO_INCREMENT`, 쿠폰 코드·QR 토큰은 `BINARY(16)`이다.
UUID는 API에서 문자열로 전달하고 Java에서 `UUID`로 다룬다. 현재 QR은 원본 난수 UUID 저장 방식이며 SHA-256 해시는 사용하지 않는다.
`user_coupon`의 `(event_id, coupon_code)`는 실제 재고를 참조하는 복합 FK다.
발급 횟수 날짜 `limit_date`는 `DATE(created_at + INTERVAL 9 HOUR)` 생성 컬럼이다.
다음 날에는 새 한도 행을 만들며 어제 행의 생성 시각을 덮어쓰지 않는다.

## 3. API와 서비스 메서드

기존 `/api/v1` URL과 요청·응답 필드는 패키지 정리 후에도 유지한다.

| 기능 | HTTP 요청 | 주요 입력 | CouponService 메서드 / 결과 |
| --- | --- | --- | --- |
| 발급 | POST /api/v1/coupon-events/{eventId}/coupons | body: userId | issue / 201, 쿠폰 ID·코드·발급 시각 |
| QR 교체 | POST /api/v1/user-coupons/{id}/qr | body: userId | rotateQr / 200, 토큰·버전·만료 시각 |
| 사용 | POST /api/v1/coupon-usages | body: qrToken, storeId, ownerId, orderAmount, menuId?, menuAmount? | use / 200, 사용 기록·할인액·사용 시각 |
| 목록 | GET /api/v1/users/{userId}/coupons | query: page=0, size=20 | list / 200, 최신 발급순 페이지 |
| 상세 | GET /api/v1/user-coupons/{id} | query: userId | detail / 200, 쿠폰·선택적인 사용 결과 |

목록 크기는 1~100이다. `orderAmount`, `menuAmount`, 할인액의 단위는 원이다.
전체 주문 할인은 메뉴 입력을 생략할 수 있고, 특정 메뉴 할인은 해당 메뉴 ID와 금액이 필요하다.
적재용 HTTP API는 없다. `prepareEvent`/`loadInventory`를 명시적으로 호출하거나 테스트 SQL로 적재한다.
앱 시작 시 자동 적재하지 않는다.

## 4. 쿠폰 적재 흐름

예: 캠페인의 오늘 수량이 100장이면 이벤트 1행과 재고 100행을 만든다. 아직 사용자 쿠폰은 없다.

1. 캠페인은 미리 저장되어 있어야 한다.
2. `prepareEvent(campaignId, date, issueStart, issueEnd, now)`가 캠페인을 잠근다.
3. 해당 영업일 이벤트가 없으면 ACTIVE로 생성한다. 한국 영업일과 시각을 결합해 UTC로 저장한다.
4. `loadInventory(eventId, now)`가 이벤트를 잠그고 순번 1~100, UUID, AVAILABLE 재고를 생성한다.
5. 이벤트와 재고를 한 번에 커밋한다. 중간 실패는 모두 롤백한다.

같은 요청을 반복하면 완성된 이벤트·재고를 그대로 반환한다. 일부만 적재된 상태는 자동 보충하지 않고 오류로 처리한다.
배치 중간의 `flush()`는 커밋이 아니다. 단독 `loadInventory` 호출도 하나의 트랜잭션이다.

시작 전부터 ACTIVE여도 된다. 발급 시작이 11시라면 API가 `issue_start_at`을 검사하여 11시 이전을 거절한다.
SCHEDULED나 10시 59분 상태 변경 작업은 없다. 종료 시각은 미포함이다.

대표 SQL(컬럼·별칭은 실제 JPA SQL과 다를 수 있음):

```sql
SELECT * FROM campaign WHERE id = :campaignId FOR UPDATE;
SELECT * FROM coupon_event
WHERE campaign_id = :campaignId AND business_date = :date;
-- 이벤트가 없으면 INSERT, 이후 재고 준비
SELECT * FROM coupon_event WHERE id = :eventId FOR UPDATE;
SELECT COUNT(*) FROM coupon_inventory WHERE event_id = :eventId;
-- 재고가 없을 때만 설정 수량만큼 INSERT
```

## 5. 쿠폰 발급 흐름

`issue(new IssueCouponCommand(eventId, userId))`를 호출한다.

1. 같은 이벤트·사용자의 쿠폰이 이미 있으면 그 쿠폰을 반환한다. 재고와 한도를 다시 차감하지 않는다.
2. 신규 발급은 독립 READ_COMMITTED 트랜잭션을 시작한다.
3. 활성 사용자인지 확인하고 오늘 한도 행을 INSERT 또는 잠근다.
4. 잠금 대기 중 다른 요청이 발급했을 수 있으므로 기존 쿠폰을 다시 확인한다.
5. 한국 날짜가 바뀌지 않았는지, 이벤트·캠페인이 ACTIVE이고 발급 시간 안인지 검사한다.
6. 오늘 횟수가 3 미만일 때만 1 증가시킨다.
7. 잠기지 않은 AVAILABLE 재고 한 행을 선점한다. 잠금 후 날짜·발급 기간을 재확인한다.
8. 재고를 ISSUED로 변경하고 사용자 쿠폰을 생성한 뒤 함께 커밋한다.

```sql
INSERT INTO coupon_daily_limit (user_id, issued_count, created_at, updated_at)
VALUES (:userId, 0, :now, :now)
ON DUPLICATE KEY UPDATE id = id;

UPDATE coupon_daily_limit
SET issued_count = issued_count + 1, updated_at = :now
WHERE user_id = :userId AND limit_date = :koreanDate AND issued_count < 3;

SELECT * FROM coupon_inventory
WHERE event_id = :eventId AND status = 'AVAILABLE'
ORDER BY sequence_no
LIMIT 1 FOR UPDATE SKIP LOCKED;
-- 선택한 재고 UPDATE + user_coupon INSERT 후 COMMIT
```

선점할 행이 없으면 한도 증가도 롤백한다. **롤백 후** AVAILABLE 행의 존재를 비잠금 조회한다.
있으면 `INVENTORY_BUSY`, 없으면 `COUPON_SOLD_OUT`이다. 정상 발급마다 재고 전체 COUNT를 수행하지 않는다.
이 판정은 조회 시점의 상태이며, 이벤트 상태를 SOLD_OUT으로 저장하거나 도메인 이벤트를 발행하지는 않는다.
순번은 선점 후보 정렬 기준이지 커밋 완료 순서가 아니다. 마지막 순번 발급만으로 전체 발급 완료를 판단하지 않는다.

### 왜 서비스 하나인데 TransactionTemplate을 쓰는가?

한 서비스 안의 메서드를 직접 호출하면 내부 메서드의 `@Transactional`만으로 새 트랜잭션을 열 수 없다.
발급 실패 롤백과 품절 확인의 순서를 확실하게 분리하려고 `issue()`는 NOT_SUPPORTED,
실제 발급은 `TransactionTemplate`의 REQUIRES_NEW + READ_COMMITTED로 실행한다.
호출자의 트랜잭션이 있어도 일시 중단하며, 발급이 완료되면 독립적으로 커밋된다.
따라서 외부 작업과 발급을 함께 롤백해야 하는 유스케이스에는 이 메서드를 그대로 조합하면 안 된다.

다른 공개 메서드는 일반 `@Transactional`을 사용한다. `prepareEvent` 내부의 `loadInventory`는
이미 열린 적재 트랜잭션에 참여하며, 별도 커밋이 필요하지 않다.

## 6. 쿠폰 사용 흐름

### QR 발급·교체

1. 쿠폰 ID와 소유 사용자 ID로 쿠폰을 잠근다.
2. ISSUED 상태 및 사용 가능한 기간인지 확인한다.
3. 새 난수 UUID로 `qr_token`을 교체하고 `qr_version`을 1 증가시킨다.
4. 만료는 `현재 초 단위 시각 + 60초`와 `쿠폰 사용 종료 시각` 중 빠른 값으로 저장한다.

최초 쿠폰은 버전 0, 토큰과 QR 만료는 null이다. 새 토큰 저장 후 이전 토큰으로는 조회되지 않는다.
클라이언트가 QR 교체 API를 호출해야 새 토큰이 생긴다. 서버가 60초마다 자동 교체하지 않으며,
추가 요청이 없어도 기존 토큰은 만료 검사로 사용할 수 없게 된다. QR 이미지 렌더링은 이 서버의 범위가 아니다.

### 매장에서 사용

1. 스캔한 현재 토큰으로 사용자 쿠폰을 잠근다(토큰 유니크 인덱스 조회).
2. 연결된 이벤트·캠페인에서 가게·점주와 할인 조건을 읽는다.
3. 주문·메뉴·최소 금액을 검사하고 실제 할인액을 계산한다.
4. 잠금 대기 후 현재 시각으로 사용 기간과 QR 만료를 검사한다.
5. 조건부 UPDATE로 USED 전환, QR 토큰·만료를 null로 지운다.
6. 사용 기록을 INSERT하고 함께 커밋한다. 기록 저장 실패 시 USED 전환도 롤백한다.

```sql
SELECT * FROM user_coupon WHERE qr_token = :binaryToken FOR UPDATE;
UPDATE user_coupon
SET status = 'USED', qr_token = NULL, qr_expires_at = NULL, updated_at = :now
WHERE id = :id AND status = 'ISSUED' AND qr_token = :binaryToken
  AND usable_start_time <= :now AND usable_end_time > :now
  AND qr_expires_at > :now;
INSERT INTO coupon_usage_history
  (user_coupon_id, discount_amount, created_at, updated_at)
VALUES (:id, :discountAmount, :now, :now);
```

한 쿠폰의 중복 사용은 행 잠금·조건부 UPDATE·사용 기록의 UNIQUE 제약으로 방어한다.
사용 기록에 가게 ID는 중복 저장하지 않으며 쿠폰 → 이벤트 → 캠페인으로 찾는다.

## 7. 쿠폰 조회 흐름

목록은 사용자 ID로 조회하고 생성 시각·ID 내림차순으로 정렬한다.
상세는 쿠폰 ID와 사용자 ID를 함께 검사한 뒤 사용 기록 0~1건을 별도로 읽는다.

```sql
SELECT * FROM user_coupon WHERE user_id = :userId
ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset;
-- Page의 전체 건수 산출에는 필요 시 별도 COUNT 쿼리가 사용된다.
SELECT * FROM user_coupon WHERE id = :id AND user_id = :userId;
SELECT * FROM coupon_usage_history WHERE user_coupon_id = :id;
```

미사용 쿠폰의 사용 기간이 끝났으면 응답 상태를 EXPIRED로 계산한다. 조회가 DB 상태를 UPDATE하지는 않는다.
목록·상세에는 QR 토큰을 반환하지 않는다. 사용 전 상세의 할인액·사용 시각은 null이다.

## 8. 시간·제약과 현재 범위

- 저장 시간은 UTC DATETIME(초 단위), 영업일·일일 한도는 Asia/Seoul 기준이다.
- 캠페인 TIME은 한국 현지 시각이며 이벤트에서 날짜를 결합한다. 종료가 시작 이하이면 다음 날 종료다.
- DB 스키마의 원본은 Flyway SQL이다. JPA는 `ddl-auto: validate`로 검증만 한다.
- Flyway V1/V2는 과거 이력 번호다. 애플리케이션 패키지 v1/v2와 관계없다.
- V3는 기존 연습 테이블을 재생성하므로 실데이터 DB에 적용하면 안 된다. V4는 예약 상태를 제거한다.
- 인증·인가는 미구현이다. 요청 userId/ownerId·주문 금액을 운영 환경에서 그대로 신뢰하면 안 된다.
- 가게·점주·메뉴 관리, 노출 타기팅, 예산 차감, 품절 이벤트/outbox, 삭제·취소 API는 미구현이다.
- 발급 후 할인 조건은 변경하지 않는 전제다. 수정 허용 시 발급 당시 조건을 별도 저장해야 한다.

## 9. 실행·검증·부하테스트

```sh
docker compose up -d
MANAGEMENT_ADDRESS=0.0.0.0 ./gradlew bootRun
```

앱 시작으로 데이터는 생기지 않는다. 마이그레이션 완료 후 테스트 전용 DB에서 k6로 적재한다.

```sh
docker compose --profile loadtest build k6
K6_VERSION=v1 K6_ALLOW_DB_WRITES=1 docker compose --profile loadtest run --rm k6
```

k6의 setup → HTTP 부하 → DB 정합성 검증 → 실행 ID별 삭제까지 한 번에 수행한다.
스크립트는 `loadtest/k6/v1/`에 두며 미래 v2는 별도 디렉터리를 사용한다.
[설정·결과·정리 실패 복구](../load-testing.md) / [버전 추가 규칙](../../loadtest/k6/README.md)

```sh
./gradlew test javadoc
node --test loadtest/k6/v1/fixture.test.mjs
```

통합 테스트 `CouponServiceIntegrationTests`는 별도 MySQL Testcontainers에서 재고·동시 발급·한도·QR·사용·조회와
단일 서비스의 트랜잭션 경계를 검증한다. `SchemaMigrationTests`는 마이그레이션 전환을 검증한다.
로컬 개발 DB를 초기화하지 않는다. 생성된 Java API 문서는 `build/docs/javadoc/index.html`에서 확인한다.
