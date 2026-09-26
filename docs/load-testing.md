# 쿠폰 발급 부하테스트

k6 한 번 실행으로 **테스트 데이터 적재 → HTTP 부하 → DB 결과 검증 → 해당 데이터 삭제**를 수행한다.
서버의 `loadtest` 패키지·프로필·내부 테스트 API는 사용하지 않는다.

현재 구현은 **v1**이며 스크립트는 `loadtest/k6/v1/`에 있다.
`K6_VERSION=v1`(기본값)은 실행 디렉터리만 선택하며 API URL을 치환하지 않는다.
v2 추가 시 자체 API·적재·검증 SQL을 `loadtest/k6/v2/`에 작성한다. 아직 v2 실행 파일은 없다.
자세한 파일 역할과 버전 추가 규칙은 [k6 디렉터리 안내](../loadtest/k6/README.md)를 참고한다.
실제 실행 수치, 확인된 장애 징후, 미확정 원인과 다음 실험은 [부하테스트 실험 기록](load-testing-results.md)에 누적한다.

## 실행 순서

1. `setup()`: 고유 실행 ID를 만들고 사용자·캠페인·ACTIVE 이벤트·AVAILABLE 재고를 SQL로 적재한다.
2. `default()`: 실제 쿠폰 발급 API만 호출한다. 측정 중 DB 직접 조회는 하지 않는다.
3. `teardown()`: 커밋된 DB 데이터로 수량·중복·재고 매칭·일일 한도를 검증하고 결과를 출력한다.
4. 검증 성공/실패와 무관하게 `finally`에서 이 실행의 데이터만 FK 역순으로 삭제한다.

앱은 여전히 미리 적재된 데이터를 사용하는 구조다. 자동 스케줄링이나 앱 시작 시 데이터 생성은 없다.
테스트 실행 전 적재하는 주체만 k6로 옮겼다. 스키마 생성은 앱의 Flyway가 담당한다.

V5부터 DB 시각은 한국 시각이다. k6 SQL은 `TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())`로 한국 현재 시각을 명시해
Go SQL 커넥션 풀의 세션 시간대와 관계없이 올바른 값을 적재한다. 검증 날짜는 `DATE(created_at)`이다.
단일 연결에 `SET time_zone` 한 번을 호출하는 방식은 풀의 다른 연결까지 보장하지 못하므로 사용하지 않는다.
V4 이하 DB에서는 새 스크립트를 실행하지 않는다. [V5 전환 절차](v1/README.md#한국-시간-저장으로-전환-v5)

## 준비와 실행

**폐기 가능한 로컬/테스트 DB에서만 실행한다. 운영 DB에는 절대 연결하지 않는다.**
최초 V3 마이그레이션은 구형 연습 테이블을 삭제한다. 테스트 SQL에 전체 테이블 초기화나 TRUNCATE는 없다.

프로젝트 루트에서 인프라를 시작하고 k6 이미지를 한 번 빌드한다.

```sh
docker compose up -d
docker compose --profile loadtest build k6
```

이미지는 기존 k6 0.54.0에 `xk6-sql 1.0.0`과 `MySQL 드라이버 0.1.0`을 포함한다.
버전은 [Dockerfile](../loadtest/Dockerfile)에 고정했다.
최초 빌드는 이미지와 Go 모듈 다운로드가 필요하다.

별도 터미널에서 API 한 인스턴스를 실행한다. 부하테스트용 Spring 프로필은 필요 없다.

```sh
MANAGEMENT_ADDRESS=0.0.0.0 ./gradlew bootRun --args='--server.address=0.0.0.0'
```

앱 시작과 마이그레이션 완료 후 실행한다.

```sh
K6_ALLOW_DB_WRITES=1 docker compose --profile loadtest run --rm k6
```

`K6_ALLOW_DB_WRITES=1`을 지정하지 않으면 SQL 적재·삭제 전에 중단한다.
Compose 기본 DSN은 `mysql:3306/coupon`의 로컬 개발용 coupon 계정이다.
API의 DB와 k6의 DB는 반드시 동일해야 한다. DB 접속 권한은 테스트 DB로 제한하고 DSN을 커밋하지 않는다.
k6는 기존 고정 사용자 ID를 사용하지 않고 이번에 INSERT한 ID를 조회한다.

## 기본 시나리오와 설정

| 항목 | 환경변수 | 기본값 |
| --- | --- | --- |
| API 주소 | LOAD_TEST_BASE_URL | http://host.docker.internal:8080 |
| 시나리오 디렉터리 | K6_VERSION | v1 |
| MySQL 연결 문자열 | K6_DB_DSN | Compose 로컬 coupon DB |
| 이벤트 수 | K6_EVENT_COUNT | 10 |
| 이벤트당 쿠폰 | K6_COUPONS_PER_EVENT | 500 |
| 고유 사용자 | K6_USER_COUNT | 10000 |
| 최대 요청률 | K6_MAX_RPS | 500 |
| 선형 증가 시간 | K6_RAMP_DURATION | 40s |
| 사전 VU / 최대 VU | K6_PRE_ALLOCATED_VUS / K6_MAX_VUS | 200 / 600 |
| 적재 후 발급 유효 시간 | K6_VALID_SECONDS | 3600초 |
| 모든 재고 소진 필수 | K6_REQUIRE_SOLD_OUT | true |
| 진단용 검증·삭제 보류 | K6_DEFER_VERIFICATION | false |
| Prometheus 실행 구분 | K6_TEST_ID | coupon-선택버전 (기본 coupon-v1) |

기본 부하는 0 → 500 RPS로 40초간 증가한다. 이론상 약 10,000회이며 실제 실행 수는 dropped/interrupted iterations도 확인한다.
기본값은 이벤트 10개, 이벤트별 재고 500장으로 총 5,000장을 적재한다. 사용자 10,000명을 준비해 실행 순번마다 서로 다른 사용자로 한 번만 요청한다. 재요청이나 자동 재시도는 없다.
실행 순번을 이벤트 수로 나눈 나머지로 이벤트를 선택하므로 약 10,000건이 모두 실행되면 이벤트별로 신규 사용자 약 1,000명이 요청한다. 정상 동작이면 이벤트마다 500건 발급 후 나머지는 품절 응답을 받는다.
설정을 바꿀 때도 `K6_USER_COUNT`를 예상 실행 요청 수 이상으로 지정해야 한다. 사용자가 부족하면 같은 사용자를 재사용하지 않고 해당 iteration을 오류로 처리한다.

더 짧은 전체 흐름 확인:

```sh
K6_ALLOW_DB_WRITES=1 K6_EVENT_COUNT=2 K6_COUPONS_PER_EVENT=5 K6_USER_COUNT=100 \
K6_MAX_RPS=20 K6_RAMP_DURATION=5s K6_PRE_ALLOCATED_VUS=5 K6_MAX_VUS=20 \
docker compose --profile loadtest run --rm k6
```

짧은 테스트에서 재고를 모두 소진하지 않을 목적이면 `K6_REQUIRE_SOLD_OUT=false`로 설정한다.
이 경우에도 실제 발급 1건 이상과 모든 정합성 검사를 통과해야 한다.
적재/검증 단계 제한 시간은 각각 5분이며 발급 유효 시간은 적재 시간과 부하 지속 시간보다 넉넉해야 한다.

## 검증 결과

종료 로그의 `DB_VALIDATION` JSON에 이벤트별 수량과 `passed`가 출력된다.

- 이벤트별 전체 재고가 설정 수량과 일치하고 초과 발급이 없는가?
- ISSUED 재고와 사용자 쿠폰이 수량뿐 아니라 코드별로 일치하는가?
- 동일 이벤트·사용자의 중복 발급이 없는가?
- 한국 날짜별 발급 수가 3 이하이고 `coupon_daily_limit.issued_count`와 일치하는가?
- 기본 모드에서는 모든 이벤트의 재고가 소진되었는가?

정합성 검증 또는 삭제 실패 시 테스트는 0이 아닌 종료 코드를 반환한다.
성능 기준은 발급 요청만 대상으로 p95 < 500ms, p99 < 1s, 예상하지 않은 응답 0, dropped iterations 0이다.
기본 시나리오에는 동일 사용자의 재요청이 없다. 요청 누락·타임아웃 가능성이 있으므로 실제 발급 수는 HTTP 성공 응답 수와 별도로 DB에서 검증한다.
INVENTORY_BUSY는 다른 요청이 재고를 잠근 경우이며 품절과 구분한다.

결과는 삭제 전에 로그로 출력되고, 성능 지표는 Compose 설정에 따라 Prometheus remote-write로 전송된다.
SQL 검증 결과 JSON은 콘솔 로그를 보관해야 한다. 실패한 데이터도 자동 삭제되므로 사후 DB 분석용으로 남지 않는다.
`coupon_database_validation`, `coupon_cleanup_success` 지표로 검증/삭제 성공 여부를 확인한다.
클라이언트 요청이 타임아웃된 뒤에도 서버 처리가 계속될 수 있다. 현행 `teardown()`은 서버 측 요청 종료를 확인하지 않고 검증·삭제하므로,
타임아웃이 발생한 실행의 검증 결과는 그 시점의 스냅샷으로 해석한다. 이 한계와 개선 순서는 [실험 기록](load-testing-results.md)에 남긴다.

### 장애 진단 시 검증·정리 분리

타임아웃이나 연결 고갈을 재현할 때는 테스트 데이터를 즉시 삭제하지 않고, **폐기 가능한 격리 DB**에서 다음 순서로 실행한다.

1. `K6_DEFER_VERIFICATION=true`로 발급 부하만 실행하고 출력된 `RUN_ID`를 보관한다. 이 실행의 종료 코드는 HTTP 성능만 판정하며 DB 정합성 완료를 뜻하지 않는다.
2. 서버에 진행 중인 발급 요청이 없는지 확인한다. 완료를 확인할 수 없으면 **해당 테스트 앱만 중지**하고, DB에서 진행 중인 트랜잭션이 정리됐는지 확인한다.
3. 동일한 테스트 DB에서 `K6_MODE=verify K6_RUN_ID=<RUN_ID>`로 DB를 읽기 전용 검증한다. 실행할 때의 `K6_EVENT_COUNT`, `K6_COUPONS_PER_EVENT`, `K6_REQUIRE_SOLD_OUT` 값도 동일하게 지정한다.
4. 진단 자료를 보관한 뒤 `K6_MODE=cleanup K6_RUN_ID=<RUN_ID> K6_ALLOW_DB_WRITES=1`로 해당 실행 데이터만 삭제한다. 검증이 실패해도 자료 확인 후 별도로 정리한다.

`K6_DEFER_VERIFICATION=true`는 서버 종료를 자동 감지하지 않는다. 앱이 계속 처리 중일 때 `verify` 또는 `cleanup`을 실행하면 안 된다.
일반 모드의 적재→부하→검증→삭제 동작은 그대로 유지된다.

## 안전한 삭제와 강제 종료 복구

각 실행의 32자리 `RUN_ID`를 첫 SQL 쓰기 전에 출력한다.

- 캠페인 마커: `notice = 'k6-coupon:<RUN_ID>'`
- 사용자 마커: `provider_user_id = 'k6-coupon:<RUN_ID>:<순번>'`
- 삭제 순서: 사용 기록 → 사용자 쿠폰 → 재고 → 일일 한도 → 이벤트 → 캠페인 → 사용자
- 다른 실행이나 기존 데이터는 수정하지 않는다.
- 다른 실행의 사용자가 테스트 이벤트를 참조하는 등 교차 참조가 있으면 삭제를 거절한다.
- `setup()` 중 예외가 발생하면 해당 함수에서 부분 적재 데이터 정리를 시도한다.
- 컨테이너 강제 종료, timeout, DB 장애에서는 정리가 보장되지 않는다. 실패 로그의 RUN_ID를 보관한다.

**이전 실행과 관련 API 처리가 완전히 중단된 것을 확인한 후**, 해당 ID만 정리한다.

```sh
K6_ALLOW_DB_WRITES=1 K6_MODE=cleanup K6_RUN_ID=<로그의_32자리_RUN_ID> \
docker compose --profile loadtest run --rm k6
```

위의 꺾쇠 부분은 실제 ID로 교체한다. 같은 ID의 정리를 반복해도 안전하다.
복구 모드는 적재·부하·검증 없이 해당 실행만 삭제한다.
남은 모든 테스트 데이터를 자동으로 지우지는 않는다. 자동 삭제는 실행 중인 다른 테스트를 침범할 수 있기 때문이다.
삭제 데이터는 이 스크립트로 복구할 수 없다. AUTO_INCREMENT 값은 되돌리지 않는다.

## 스크립트 회귀 테스트

Node.js 22 이상이 있으면 DB 없이 ID 매핑·삭제 범위 방어 로직을 확인한다.

```sh
node --test loadtest/k6/v1/fixture.test.mjs
```

MySQL SQL 회귀 테스트는 동일한 폐기 가능한 테스트 DB에서 실행한다.
두 독립 실행의 데이터를 생성해 검증 실패 탐지, 타 실행 보존, 교차 참조 삭제 거절, FK 순서, 반복 삭제를 확인한다.

```sh
K6_ALLOW_DB_WRITES=1 docker compose --profile loadtest run --rm k6 \
run /scripts/v1/fixture-regression.js
```

서버 기능 검증은 `./gradlew test`의 별도 MySQL Testcontainers에서 수행한다.
예전 Java 대량 fixture 테스트는 제거하고 실제 k6 SQL 회귀 테스트로 대체했다.

참고: [k6 SQL 확장](https://github.com/grafana/xk6-sql), [k6 실행 단계](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/).
