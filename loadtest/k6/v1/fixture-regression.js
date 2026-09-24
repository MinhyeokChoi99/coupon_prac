// 부하 성능이 아닌 SQL 적재/검증/삭제 안전성 회귀 테스트. 폐기 가능한 DB에서만 실행한다.
import sql from 'k6/x/sql';
import mysql from 'k6/x/sql/driver/mysql';
import crypto from 'k6/crypto';
import { cleanupFixture, runMarker, seedFixture, verifyFixture, userAt } from './fixture.js';

export const options = { vus: 1, iterations: 1, setupTimeout: '2m' };
export default function () {}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function newRunId() {
  return Array.from(new Uint8Array(crypto.randomBytes(16)))
    .map(byte => byte.toString(16).padStart(2, '0')).join('');
}

export function setup() {
  if (__ENV.K6_ALLOW_DB_WRITES !== '1' || !__ENV.K6_DB_DSN) throw new Error('Disposable DB opt-in and DSN required');
  const db = sql.open(mysql, __ENV.K6_DB_DSN);
  const first = newRunId();
  const second = newRunId();
  const partial = newRunId();
  const config = { eventCount: 1, quantity: 1, userCount: 5, validSeconds: 600, requireSoldOut: true };
  try {
    const failingDb = {
      query: (query, ...args) => db.query(query, ...args),
      exec: (query, ...args) => {
        if (query.includes('INSERT INTO coupon_inventory')) throw new Error('Injected partial seed failure');
        return db.exec(query, ...args);
      },
    };
    let seedFailed = false;
    try { seedFixture(failingDb, partial, config); } catch (error) {
      seedFailed = error.message.includes('Injected partial seed failure');
    }
    assert(seedFailed, 'partial seed failure must be reproducible');
    cleanupFixture(db, partial);
    const a = seedFixture(db, first, config);
    const b = seedFixture(db, second, config);
    assert(!verifyFixture(db, a).passed, 'no successful issuance must fail validation');
    const id = db.exec(`INSERT INTO user_coupon
      (event_id, user_id, coupon_code, status, usable_start_time, usable_end_time, created_at, updated_at)
      SELECT event_id, ?, coupon_code, 'ISSUED', usable_start_time, usable_end_time, UTC_TIMESTAMP(), UTC_TIMESTAMP()
      FROM coupon_inventory WHERE event_id = ?`, userAt(a.userRanges, 0), a.eventIds[0]).lastInsertId();
    db.exec("UPDATE coupon_inventory SET status = 'ISSUED' WHERE event_id = ?", a.eventIds[0]);
    db.exec(`INSERT INTO coupon_daily_limit (user_id, issued_count, created_at, updated_at)
      VALUES (?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())`, userAt(a.userRanges, 0));
    assert(verifyFixture(db, a).passed, 'valid issuance must pass');
    db.exec('UPDATE coupon_daily_limit SET issued_count = 2 WHERE user_id = ?', userAt(a.userRanges, 0));
    assert(!verifyFixture(db, a).passed, 'daily count mismatch must fail');
    db.exec('UPDATE coupon_daily_limit SET issued_count = 1 WHERE user_id = ?', userAt(a.userRanges, 0));
    db.exec("UPDATE coupon_inventory SET status = 'AVAILABLE' WHERE event_id = ?", a.eventIds[0]);
    assert(!verifyFixture(db, a).passed, 'inventory mismatch must fail');
    db.exec("UPDATE coupon_inventory SET status = 'ISSUED' WHERE event_id = ?", a.eventIds[0]);

    // 타 실행 사용자로 잘못 연결하면 삭제를 거절하고 아무것도 삭제하지 않아야 한다.
    db.exec('UPDATE user_coupon SET user_id = ? WHERE id = ?', userAt(b.userRanges, 0), id);
    let refused = false;
    try { cleanupFixture(db, first); } catch (error) { refused = error.message.includes('Cleanup refused'); }
    db.exec('UPDATE user_coupon SET user_id = ? WHERE id = ?', userAt(a.userRanges, 0), id);
    assert(refused, 'cross-run reference must block cleanup');
    db.exec(`INSERT INTO coupon_usage_history (user_coupon_id, discount_amount, created_at, updated_at)
      VALUES (?, 1000, UTC_TIMESTAMP(), UTC_TIMESTAMP())`, id);
    cleanupFixture(db, first);
    cleanupFixture(db, first); // 중복 정리도 안전해야 한다.
    const remaining = db.query('SELECT COUNT(*) n FROM campaign WHERE notice = ?', runMarker(second));
    assert(Number(remaining[0].n) === 1, 'cleanup must preserve the other run');
    const users = db.query('SELECT COUNT(*) n FROM `user` WHERE provider_user_id LIKE ?', runMarker(second) + ':%');
    assert(Number(users[0].n) === 5, 'cleanup must preserve other users');
    console.log('Fixture regression passed: verification failures, scoped cleanup, FK order, repeat cleanup');
  } finally {
    try {
      cleanupFixture(db, first);
      cleanupFixture(db, second);
      cleanupFixture(db, partial);
    } finally {
      db.close();
    }
  }
}
