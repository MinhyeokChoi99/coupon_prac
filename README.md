# Coupon Prac

Spring Boot 4.1.1 / Java 25 기반 쿠폰 발급 프로젝트. V1은 JPA·Redis·`@Scheduled`를 사용한다.

## 로컬 환경

```sh
docker compose up -d
MANAGEMENT_ADDRESS=0.0.0.0 ./gradlew bootRun
```

- Grafana: http://localhost:3000 — `admin` / `coupon-admin`
- Prometheus: http://localhost:9090

[실행 및 모니터링 안내](docs/local-development.md) · [스케줄러 설계](docs/scheduled-jobs.md)

현재는 실행·모니터링 기반 설정 단계이며 쿠폰 발급 API와 정기 작업은 아직 구현하지 않았다.
