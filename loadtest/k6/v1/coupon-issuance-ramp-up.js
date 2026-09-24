import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import sql from 'k6/x/sql';
import mysql from 'k6/x/sql/driver/mysql';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import {
  positiveInteger, runMarker, seedFixture, verifyFixture, cleanupFixture, userAt, userIndexFor,
} from './fixture.js';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const mode = __ENV.K6_MODE || 'run';
if (!['run', 'cleanup'].includes(mode)) throw new Error('K6_MODE must be run or cleanup');
const maxRps = positiveInteger(__ENV.K6_MAX_RPS || 2000, 'K6_MAX_RPS', 100000);
const rampDuration = __ENV.K6_RAMP_DURATION || '60s';
const preAllocatedVUs = positiveInteger(__ENV.K6_PRE_ALLOCATED_VUS || 1000, 'K6_PRE_ALLOCATED_VUS', 10000);
const maxVUs = positiveInteger(__ENV.K6_MAX_VUS || 3000, 'K6_MAX_VUS', 10000);
const testId = __ENV.K6_TEST_ID || 'coupon-v1';
const config = {
  eventCount: positiveInteger(__ENV.K6_EVENT_COUNT || 30, 'K6_EVENT_COUNT', 1000),
  quantity: positiveInteger(__ENV.K6_COUPONS_PER_EVENT || 500, 'K6_COUPONS_PER_EVENT', 100000),
  userCount: positiveInteger(__ENV.K6_USER_COUNT || 50000, 'K6_USER_COUNT', 1000000),
  validSeconds: positiveInteger(__ENV.K6_VALID_SECONDS || 3600, 'K6_VALID_SECONDS', 86400),
  requireSoldOut: (__ENV.K6_REQUIRE_SOLD_OUT || 'true') === 'true',
};
if (config.userCount % 5 !== 0) throw new Error('K6_USER_COUNT must be a multiple of 5');
if (!['true', 'false'].includes(__ENV.K6_REQUIRE_SOLD_OUT || 'true')) throw new Error('K6_REQUIRE_SOLD_OUT must be true or false');
if (maxVUs < preAllocatedVUs) throw new Error('K6_MAX_VUS must be >= K6_PRE_ALLOCATED_VUS');

const successfulResponse = new Counter('coupon_issue_success_response');
const soldOut = new Counter('coupon_issue_sold_out');
const inventoryBusy = new Counter('coupon_issue_inventory_busy');
const dailyLimitRejected = new Counter('coupon_issue_daily_limit_rejected');
const unexpectedResponse = new Counter('coupon_issue_unexpected_response');
const databaseValidation = new Rate('coupon_database_validation');
const cleanupSuccess = new Rate('coupon_cleanup_success');

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  setupTimeout: '5m',
  teardownTimeout: '5m',
  scenarios: mode === 'cleanup'
    ? { cleanup_only: { executor: 'shared-iterations', vus: 1, iterations: 1 } }
    : {
      coupon_issuance_ramp_up: {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1s',
        preAllocatedVUs,
        maxVUs,
        stages: [{ target: maxRps, duration: rampDuration }],
        gracefulStop: '30s',
      },
    },
  thresholds: mode === 'cleanup' ? { coupon_cleanup_success: ['rate==1'] } : {
    'http_req_duration{name:issue_coupon}': ['p(95)<500', 'p(99)<1000'],
    'http_req_failed{name:issue_coupon}': ['rate==0'],
    dropped_iterations: ['count==0'],
    coupon_issue_unexpected_response: ['count==0'],
    coupon_database_validation: ['rate==1'],
    coupon_cleanup_success: ['rate==1'],
  },
  tags: { testid: testId },
};

/**
 * 명시적 DB 쓰기 허용과 DSN을 검사한 뒤 준비/종료 단계용 SQL 연결을 연다. VU는 호출하지 않는다.
 * @returns {object} 호출자가 finally에서 close해야 하는 xk6-sql 연결.
 * @throws {Error} K6_ALLOW_DB_WRITES=1 또는 K6_DB_DSN이 없는 경우.
 */
function openDatabase() {
  if (__ENV.K6_ALLOW_DB_WRITES !== '1') throw new Error('Set K6_ALLOW_DB_WRITES=1 for a disposable test DB only');
  if (!__ENV.K6_DB_DSN) throw new Error('K6_DB_DSN is required; it must point to the same test DB as the API');
  return sql.open(mysql, __ENV.K6_DB_DSN);
}

/**
 * 실행마다 새 마커로 DB 데이터를 적재하고 조회 API로 서버 연결을 확인한다.
 * 실패 시 부분 적재를 정리하며 기존 실행 ID 충돌의 데이터는 건드리지 않는다.
 * @returns {object} VU와 teardown에 전달할 fixture. cleanup 모드는 정리 후 cleanupOnly=true를 반환한다.
 * @throws {Error} 적재·연결 확인·복구 모드 삭제에 실패한 경우.
 */
export function setup() {
  const db = openDatabase();
  if (mode === 'cleanup') {
    try {
      runMarker(__ENV.K6_RUN_ID);
      cleanupFixture(db, __ENV.K6_RUN_ID);
      cleanupSuccess.add(true);
      return { cleanupOnly: true };
    } catch (error) {
      cleanupSuccess.add(false);
      throw error;
    } finally {
      db.close();
    }
  }
  const runId = Array.from(new Uint8Array(crypto.randomBytes(16)))
    .map(byte => byte.toString(16).padStart(2, '0')).join('');
  console.log('RUN_ID=' + runId);
  try {
    const data = seedFixture(db, runId, config);
    const response = http.get(
      baseUrl + '/api/v1/users/' + userAt(data.userRanges, 0) + '/coupons',
      { timeout: '5s', tags: { name: 'fixture_preflight' } },
    );
    if (response.status !== 200) throw new Error('API preflight failed: HTTP ' + response.status);
    console.log('FIXTURE ready: ' + JSON.stringify({ runId, events: config.eventCount, users: config.userCount, inventory: config.eventCount * config.quantity }));
    return data;
  } catch (error) {
    // setup 실패 시 k6가 teardown을 호출하지 않으므로 여기서 부분 적재를 정리한다.
    // 충돌한 실행 ID의 데이터는 이번 실행이 만든 것이 아니므로 건드리지 않는다.
    if (error.code === 'RUN_ALREADY_EXISTS') throw error;
    try {
      cleanupFixture(db, runId);
      cleanupSuccess.add(true);
    } catch (cleanupError) {
      cleanupSuccess.add(false);
      console.error('Cleanup failed; recover with K6_MODE=cleanup K6_RUN_ID=' + runId + ': ' + cleanupError.message);
    }
    throw error;
  } finally {
    db.close();
  }
}

/**
 * 한 iteration에서 사용자·이벤트를 선택해 v1 발급 API를 한 번 호출하고 응답을 분류한다.
 * @param {object} data setup에서 받은 사용자 ID 구간·이벤트 목록·설정 또는 cleanupOnly 표시.
 * @returns {void} HTTP·업무 결과 메트릭만 기록한다. DB 직접 조회와 응답 기반 재시도는 없다.
 */
export default function (data) {
  if (data.cleanupOnly) return;
  const index = userIndexFor(exec.scenario.iterationInTest, data.config.userCount);
  const userId = userAt(data.userRanges, index);
  const eventId = data.eventIds[index % data.eventIds.length];
  const response = http.post(
    baseUrl + '/api/v1/coupon-events/' + eventId + '/coupons',
    JSON.stringify({ userId }),
    { timeout: '10s', headers: { 'Content-Type': 'application/json' }, tags: { name: 'issue_coupon' } },
  );
  classify(response);
}

/**
 * 부하 종료 후 DB 정합성을 검증하고, 검증 실패 여부와 무관하게 finally에서 실행 데이터를 삭제한다.
 * @param {object} data setup이 반환한 실행 ID와 기대 수량. cleanupOnly이면 추가 작업하지 않는다.
 * @returns {void} 삭제 전에 보고서를 출력하고 검증·정리 성공률 메트릭을 기록한다.
 * @throws {Error} DB 검증 또는 정리에 실패한 경우. 강제 종료 시 이 함수 실행은 보장되지 않는다.
 */
export function teardown(data) {
  if (data.cleanupOnly) return;
  const db = openDatabase();
  try {
    const report = verifyFixture(db, data);
    console.log('DB_VALIDATION ' + JSON.stringify(report));
    if (!report.passed) throw new Error('Database validation failed; see DB_VALIDATION report');
    databaseValidation.add(true);
  } catch (error) {
    databaseValidation.add(false);
    throw error;
  } finally {
    try {
      cleanupFixture(db, data.runId);
      cleanupSuccess.add(true);
    } catch (error) {
      cleanupSuccess.add(false);
      console.error('Recover with K6_MODE=cleanup K6_RUN_ID=' + data.runId);
      throw error;
    } finally {
      db.close();
    }
  }
}

/**
 * 발급 응답을 성공·품절·잠금 경합·일일 한도·예상 밖 오류 카운터로 분류한다.
 * @param {object} response k6 HTTP 발급 응답. 201은 멱등 재응답도 포함한다.
 * @returns {void} 결과 카운터를 증가시키며 예상 밖 결과는 실패 check로 남긴다.
 */
function classify(response) {
  if (response.status === 201) {
    successfulResponse.add(1);
    return;
  }
  let errorCode = null;
  if (response.status === 409) {
    try { errorCode = response.json('code'); } catch (_) { /* malformed response is unexpected */ }
  }
  if (errorCode === 'COUPON_SOLD_OUT') soldOut.add(1);
  else if (errorCode === 'INVENTORY_BUSY') inventoryBusy.add(1);
  else if (errorCode === 'DAILY_ISSUANCE_LIMIT_EXCEEDED') dailyLimitRejected.add(1);
  else {
    unexpectedResponse.add(1);
    check(response, { 'expected issuance outcome': () => false });
  }
}
