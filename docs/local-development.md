# Docker Desktop 로컬 실행

Spring은 IDE 또는 `bootRun`으로 실행하고, 인프라는 루트의 `compose.yaml`로 실행한다. 애플리케이션 설정은 `src/main/resources/application.yaml` 한 파일에 유지한다. `infra/` 아래 파일은 Prometheus·Grafana 등 각 도구의 설정이다.

## 인프라 시작

Docker Desktop을 실행한 뒤 프로젝트 루트에서:

```sh
docker compose up -d
```

MySQL, MySQL exporter, Prometheus, Grafana가 실행된다. Docker Desktop의 Containers에서 `coupon-prac` 프로젝트로 표시된다.

| 서비스 | 접속 주소 | 로컬 계정 |
| --- | --- | --- |
| MySQL | localhost:3306 / coupon DB | coupon / coupon |
| Prometheus | http://localhost:9090 | 인증 없음 |
| Grafana | http://localhost:3000 | admin / coupon-admin |

Compose의 계정은 로컬 개발용이다. MySQL exporter는 별도 계정과 최대 연결 수 3개를 사용한다. 초기 계정 생성 SQL은 빈 MySQL 볼륨의 최초 시작 때만 실행된다. 기존 볼륨이 있으면 Compose 암호를 바꿔도 DB 계정이 자동 변경되지는 않는다.

## Spring 실행과 메트릭 확인

API (8080, Actuator 9091):

```sh
MANAGEMENT_ADDRESS=0.0.0.0 ./gradlew bootRun
```

앱은 DB에 미리 적재한 이벤트·재고를 사용한다. 예약 실행 프로필이나 자동 데이터 생성은 없다.
현재 시각은 `LocalDateTime.now()`이며 `bootRun`은 JVM 시간대를 `Asia/Seoul`로 지정한다.
IDE는 한국 OS/JVM 기본 시간대를 사용하거나 VM 옵션 `-Duser.timezone=Asia/Seoul`을 지정한다.
MySQL 연결 시간대는 `+09:00`이며 DB DATETIME과 API 응답은 한국 시각이다. 응답에는 `Z`가 붙지 않는다.
Java는 원본 `LocalDateTime`을 전달하고 JDBC·MySQL의 기본 소수점 처리로 `DATETIME`에 저장한다.
별도 반올림 방지 설정은 사용하지 않는다. 연습용으로 초·날짜 경계 오차를 감수하며 자정 직전 한도 판정이 어긋날 수 있다.
캠페인·이벤트 상태와 발급·사용 기간을 확인한 뒤 API를 실행한다. [적재 SQL 설명](current-coupon-erd.md#1-쿠폰-적재-흐름)
부하테스트 데이터는 앱 시작 후 k6가 적재하고 종료 시 검증·삭제한다. [k6 실행 안내](load-testing.md)

컨테이너에서 Mac의 Spring에 연결할 때는 `host.docker.internal`을 사용한다. 기본 `127.0.0.1` 관리 주소는 컨테이너 접근용이 아니므로 위 실행 명령에서 `MANAGEMENT_ADDRESS`를 변경한다. 관리 엔드포인트에는 인증이 없으므로 로컬 개발 환경에서만 사용하고 공유 네트워크에 공개하지 않는다.

앱의 DB 기본 설정은 Compose의 `coupon` / `coupon` 계정과 일치한다. DB 접속은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 바꿀 수 있다. MySQL 데이터베이스와 계정은 Compose의 MySQL 컨테이너가 최초 시작 시 생성한다. Spring은 계정을 생성하지 않는다.

## 대시보드

- Grafana → Dashboards → Coupon → **Coupon Overview**가 자동 등록된다.
- 직접 접속: http://localhost:3000/d/coupon-overview
- Prometheus 데이터 소스가 자동 등록된다.
- Prometheus는 15초 간격으로 메트릭을 수집한다. Targets: http://localhost:9090/targets
- Spring 프로세스를 실행하지 않으면 해당 API target은 DOWN이다. DB·Prometheus target은 인프라만 실행해도 UP이어야 한다.
- 응답시간과 요청량 패널은 실제 API 요청이 생긴 후 데이터가 나온다. Actuator 요청은 API 성능 패널에서 제외한다.
- 데이터와 메트릭은 named volume에 저장한다. Prometheus 보관 기간은 7일이다.
- API 기본 메트릭은 http://localhost:9091/actuator/prometheus, 상태 확인은 http://localhost:9091/actuator/health 이다.
- DB 상태 확인과 MySQL exporter는 실제 DB 연결을 사용한다. 애플리케이션의 메트릭 수집과는 별개다.

## 종료와 데이터 유지

```sh
docker compose down
```

컨테이너만 제거하고 볼륨은 유지한다. `down -v`는 DB·모니터링 데이터를 삭제하므로 초기화가 필요한 경우에만 사용한다. Spring을 별도로 실행했다면 해당 터미널에서 종료한다.

## JPA와 통합 테스트

JPA는 `ddl-auto: validate`를 사용하고, Flyway가 `src/main/resources/db/migration`의 SQL로 스키마를 관리한다. 현재 7개 테이블을 검증한다. V1~V3는 기존 이력, V4는 예약 상태 제거, V5는 UTC 데이터의 한국 시각 전환이다.
V5 배포 전 API·k6 쓰기를 중단하고 백업한다. [전환 및 부분 실패 주의사항](v1/README.md#한국-시간-저장으로-전환-v5)

`./gradlew test`는 Testcontainers의 독립 MySQL를 사용한다. Compose의 로컬 데이터베이스를 수정하지 않는다.
