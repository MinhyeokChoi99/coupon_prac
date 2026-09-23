# 쿠폰 발급 부하테스트

이 환경은 API 인스턴스 한 대에서 다음 ramp-up 시나리오를 재현한다.

```text
동시 이벤트: 30개
이벤트별 쿠폰: 500장
총 재고: 15,000장
고유 사용자: 50,000명
사용자당 평균 추가 재시도: 0.2회
총 요청: 60,000건
부하: 0 RPS에서 2,000 RPS까지 60초 동안 선형 증가
```

선형 증가 구간의 평균 요청률은 1,000 RPS이므로 기본 설정으로 총 60,000건이 발생한다. 이는 2,000 RPS를 유지하는 테스트가 아니다.

## 시작

먼저 MySQL, Prometheus, Grafana를 실행한다.

```sh
docker compose up -d
```

별도 터미널에서 API를 한 인스턴스만 실행한다. `loadtest` 프로필은 캠페인 30개, 이벤트 30개와 이벤트별 500개 재고, 실제 사용자 행 50,000개를 준비한다. 캠페인 `notice='coupon-prac-load-test-v3'`로 테스트 데이터를 구분한다. 발급 시 MySQL이 `FOR UPDATE SKIP LOCKED`로 재고 행 하나를 선점한다.

```sh
COUPON_LOADTEST_RESET=true MANAGEMENT_ADDRESS=0.0.0.0 ./gradlew bootRun --args='--spring.profiles.active=loadtest --server.address=0.0.0.0'
```

`COUPON_LOADTEST_RESET=true`는 위 마커가 있는 캠페인·이벤트의 사용 기록·사용자 쿠폰·재고·이벤트·캠페인과 예약 사용자 ID 범위(10,000,000~10,049,999)의 일일 한도를 삭제한 뒤 재생성한다. 사용자 행 자체는 유지한다. 예약 ID는 부하테스트 전용이어야 하며 일반 사용자와 충돌하면 실행을 중단한다.

**최초 V3 마이그레이션은 이전 구조의 연습용 4개 테이블과 데이터를 삭제한다. 실데이터 환경에서 실행하면 안 된다.** 이후 fixture reset은 위 테스트 데이터 범위로 제한된다.

K6는 시작 전에 `/internal/load-test/fixture`를 조회해 **30개 이벤트, 15,000개의 AVAILABLE 재고, 발급 쿠폰 0개, 일일 한도 행 0개**를 확인할 때까지 최대 60초 대기한다.

다음 엔드포인트가 30개 이벤트를 반환하면 준비가 끝난 것이다.

```sh
curl http://localhost:8080/internal/load-test/coupon-events
```

## 실행

```sh
docker compose --profile loadtest run --rm k6
```

K6 컨테이너는 호스트에서 실행 중인 API의 `http://host.docker.internal:8080`으로 요청한다. Linux 등 다른 주소가 필요하면 실행 시 `LOAD_TEST_BASE_URL`을 바꾼다.

```sh
LOAD_TEST_BASE_URL=http://192.168.0.10:8080 \
docker compose --profile loadtest run --rm k6
```

K6 결과는 Prometheus remote-write로 전송된다. Grafana의 `Coupon Issuance V1` 대시보드에서 실행별로 보려면 `K6_TEST_ID`를 지정한다.

```sh
K6_TEST_ID=baseline-mysql-v1 \
docker compose --profile loadtest run --rm k6
```

짧은 스모크 테스트는 아래처럼 실행할 수 있다. 이 값은 60,000건 시나리오와 정합성 결과가 다르므로 연결 확인 용도로만 사용한다.

```sh
K6_MAX_RPS=20 K6_RAMP_DURATION=5s K6_PRE_ALLOCATED_VUS=20 K6_MAX_VUS=100 \
docker compose --profile loadtest run --rm k6
```

## 모니터링과 검증

테스트 중 Grafana의 Coupon Overview 대시보드에서 다음을 확인한다.

- HTTP RPS, p95/p99, 5xx 비율
- HikariCP active/pending/max connection
- JVM heap
- MySQL connected/running threads

K6의 `coupon_issue_success_response`, `coupon_issue_sold_out`, `coupon_issue_inventory_busy`, `coupon_issue_daily_limit_rejected`, `coupon_issue_unexpected_response`도 함께 확인한다. INVENTORY_BUSY는 다른 요청이 남은 재고를 잠근 상태이며 실제 품절과 구분한다. 이 스크립트는 응답별 추가 재시도 없이 원래 60,000건 시나리오를 유지한다. 성공 응답에는 멱등 재응답도 포함될 수 있으므로, 최종 발급 수는 DB로 검증한다. 기본 시나리오의 최종 합격 조건은 아래와 같다.

```text
CouponInventory ISSUED: 15,000건
UserCoupon: 15,000건
이벤트별 UserCoupon: 500건
초과 발급: 0건
동일 이벤트·사용자 중복 발급: 0건
사용자 일일 발급 한도 초과: 0건
5xx: 0건
HTTP p95: 500ms 이하
HTTP p99: 1초 이하
```

테스트가 끝난 뒤 아래 엔드포인트의 `passed`가 `true`인지 확인한다. 이벤트별 발급 재고와 `UserCoupon` 수를 함께 반환한다. 이 엔드포인트는 `loadtest` 프로필에서만 노출된다.

```sh
curl http://localhost:8080/internal/load-test/result
```
