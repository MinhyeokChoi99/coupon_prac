# 버전별 k6 테스트

서버 코드와 같은 버전 단위로 HTTP 시나리오와 DB fixture를 관리한다.
서로 다른 스키마의 적재·삭제 SQL이 섞이지 않도록 fixture도 버전 디렉터리에 둔다.

```text
loadtest/
  Dockerfile                         # 공통 k6 + SQL 확장 실행 이미지
  k6/
    README.md
    v1/
      coupon-issuance-ramp-up.js      # setup → HTTP 부하 → 검증 → 삭제
      fixture.js                     # v1 적재·검증·범위 제한 삭제 SQL
      fixture.test.mjs               # DB 없는 Node 회귀 테스트
      fixture-regression.js          # 실제 MySQL SQL 회귀 테스트
```

## v1 실행

프로젝트 루트에서 앱과 DB를 먼저 실행하고 마이그레이션을 완료한다.
API와 k6는 같은 **폐기 가능한 테스트 DB**를 사용해야 한다.

```sh
docker compose --profile loadtest build k6
K6_VERSION=v1 K6_ALLOW_DB_WRITES=1 docker compose --profile loadtest run --rm k6
node --test loadtest/k6/v1/fixture.test.mjs
```

Compose는 `/scripts/${K6_VERSION}/coupon-issuance-ramp-up.js`를 실행한다. 미지정 시 v1이다.
`K6_TEST_ID`를 지정하지 않으면 메트릭 태그는 `coupon-v1`처럼 선택 버전을 따른다.
개별 테스트 실행은 명령 경로를 직접 지정한다.

```sh
K6_ALLOW_DB_WRITES=1 docker compose --profile loadtest run --rm k6 \
  run /scripts/v1/fixture-regression.js
```

## v2를 추가할 때

1. `v2/`에 동일한 진입 파일명 `coupon-issuance-ramp-up.js`를 만든다.
2. v2 API 경로·응답 계약과 스키마에 맞춰 `fixture.js` 및 회귀 테스트를 작성한다.
3. 실행 ID별 데이터만 삭제하는 방어, 부분 적재 실패 정리, 검증 결과 출력은 유지한다.
4. v2 앱·DB를 준비한 뒤 `K6_VERSION=v2`와 해당 `LOAD_TEST_BASE_URL`, `K6_DB_DSN`을 지정한다.

현재 v2 디렉터리나 빈 시나리오는 만들지 않았다. `K6_VERSION=v2`만 설정해도 v1이 v2로 바뀌지는 않는다.
DB가 다르면 각 버전의 DSN을 명시한다. API 버전과 Flyway `V1__`, `V2__` 마이그레이션 번호는 별개다.
이미지는 공통이므로 JS 변경만으로 재빌드할 필요는 없다(Compose 읽기 전용 볼륨으로 마운트).

전체 환경변수·결과 해석·강제 종료 후 복구는 [부하테스트 안내](../../docs/load-testing.md)를 참고한다.
