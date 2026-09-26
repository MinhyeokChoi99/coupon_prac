// k6 전용 SQL 도우미. 앱 테이블 구조를 바꾸지 않고 실행 마커에 속한 데이터만 다룬다.
const RUN_PREFIX = 'k6-coupon:';
const BATCH_SIZE = 1000;

/**
 * 실행 ID를 검증하고 SQL 적재·삭제 범위를 식별하는 마커를 만든다.
 * @param {string} runId 소문자 16진수 32자리 실행 ID. 빈 값·와일드카드는 거절한다.
 * @returns {string} campaign.notice와 사용자 식별자 접두어에 사용할 실행 마커.
 * @throws {Error} 형식이 잘못되어 안전하게 삭제 범위를 지정할 수 없는 경우.
 */
export function runMarker(runId) {
  if (!/^[a-f0-9]{32}$/.test(runId || '')) throw new Error('Invalid K6_RUN_ID: expected 32 lowercase hex characters');
  return RUN_PREFIX + runId;
}

/**
 * 환경변수나 DB 식별자를 손실 없이 다룰 수 있는 양의 정수로 변환한다.
 * @param {string|number} value 변환할 값.
 * @param {string} name 오류 메시지에 표시할 설정 이름.
 * @param {number} maximum 허용하는 최댓값(포함).
 * @returns {number} 검증된 양의 안전 정수.
 * @throws {Error} 정수가 아니거나 1~maximum 범위를 벗어난 경우.
 */
export function positiveInteger(value, name, maximum) {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number <= 0 || number > maximum) {
    throw new Error(`${name} must be an integer between 1 and ${maximum}`);
  }
  return number;
}

/**
 * VU마다 전체 사용자 ID를 복사하지 않도록 연속 ID를 구간으로 압축한다. ID 공백은 별도 구간으로 유지한다.
 * @param {Array<{id: number|string}>} rows DB에서 ID 오름차순으로 조회한 이번 실행의 사용자 행.
 * @returns {Array<{start: number, count: number}>} 시작 ID와 구간 길이 목록.
 */
export function compactUserIds(rows) {
  const ranges = [];
  for (const row of rows) {
    const id = positiveInteger(row.id, 'user ID', Number.MAX_SAFE_INTEGER);
    const last = ranges[ranges.length - 1];
    if (last && id === last.start + last.count) last.count += 1;
    else ranges.push({ start: id, count: 1 });
  }
  return ranges;
}

/**
 * 압축된 사용자 목록에서 논리 순번에 대응하는 실제 DB ID를 찾는다.
 * @param {Array<{start: number, count: number}>} ranges compactUserIds의 결과.
 * @param {number} index 0부터 시작하는 사용자 순번. 호출자는 음이 아닌 정수를 전달한다.
 * @returns {number} 이번 실행에 속한 사용자 ID.
 * @throws {Error} 순번이 준비한 사용자 수 이상인 경우.
 */
export function userAt(ranges, index) {
  for (const range of ranges) {
    if (index < range.count) return range.start + index;
    index -= range.count;
  }
  throw new Error('User index outside fixture');
}

/**
 * 집계 SQL의 첫 행 n 컬럼을 숫자로 읽는다.
 * @param {object} db 열린 xk6-sql MySQL 연결.
 * @param {string} query n 별칭의 단일 집계 값을 반환할 파라미터 SQL.
 * @param {...*} args SQL 물음표 자리의 바인딩 값.
 * @returns {number} 조회된 집계 값.
 */
function scalar(db, query, ...args) {
  return Number(db.query(query, ...args)[0].n);
}

/**
 * 새 실행의 사용자·캠페인·이벤트·재고를 적재하고 수량과 발급 기간을 검사한다.
 * AUTO_INCREMENT ID를 조회하며 기존 데이터를 덮어쓰지 않는다. 부분 실패 정리는 호출자가 담당한다.
 * @param {object} db 폐기 가능한 테스트 DB의 열린 xk6-sql 연결.
 * @param {string} runId 새 32자리 실행 ID. 기존 마커와 충돌하면 쓰기 전에 거절한다.
 * @param {object} config eventCount, quantity, userCount, validSeconds, requireSoldOut 설정.
 * @returns {object} runId, eventIds, userRanges, config를 담은 VU·검증 공유 데이터.
 * @throws {Error} 기존 실행 충돌(code=RUN_ALREADY_EXISTS), SQL 실패 또는 적재 검증 실패.
 */
export function seedFixture(db, runId, config) {
  const marker = runMarker(runId);
  if (scalar(db, 'SELECT COUNT(*) n FROM campaign WHERE notice = ?', marker) !== 0
      || scalar(db, 'SELECT COUNT(*) n FROM `user` WHERE provider_user_id LIKE ?', marker + ':%') !== 0) {
    const error = new Error('Run ID already exists; use cleanup mode after confirming the previous run stopped');
    error.code = 'RUN_ALREADY_EXISTS';
    throw error;
  }
  for (let offset = 0; offset < config.userCount; offset += BATCH_SIZE) {
    const count = Math.min(BATCH_SIZE, config.userCount - offset);
    const values = Array(count).fill("(?, 'ACTIVE', TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()), TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()))").join(',');
    const providers = Array.from({ length: count }, (_, i) => `${marker}:${offset + i}`);
    db.exec('INSERT INTO `user` (provider_user_id, status, created_at, updated_at) VALUES ' + values, ...providers);
  }
  const userRanges = compactUserIds(db.query(
    'SELECT id FROM `user` WHERE provider_user_id LIKE ? ORDER BY id', marker + ':%'));
  const eventIds = [];
  for (let i = 0; i < config.eventCount; i += 1) {
    const campaignId = db.exec(`INSERT INTO campaign
      (store_id, owner_id, status, discount_target_type, discount_type, discount_value,
       issue_quantity, usable_start_time, usable_end_time, notice, target_age_groups,
       daily_budget, start_date, end_date, created_at)
      VALUES (1, 1, 'ACTIVE', 'ALL', 'AMOUNT', 1000, ?, '00:00:00', '23:59:59', ?, '전체',
       100000, DATE(TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())),
       DATE(TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())) + INTERVAL 1 DAY, TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()))`,
    config.quantity, marker).lastInsertId();
    const eventId = db.exec(`INSERT INTO coupon_event
      (campaign_id, business_date, coupon_quantity, issue_start_at, issue_end_at,
       usable_start_time, usable_end_time, status, created_at, updated_at)
      VALUES (?, DATE(TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())), ?, TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()) - INTERVAL 1 SECOND,
       TIMESTAMPADD(SECOND, ?, TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())), TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()) - INTERVAL 1 SECOND,
       TIMESTAMPADD(SECOND, ?, TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())), 'ACTIVE', TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()), TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()))`,
    campaignId, config.quantity, config.validSeconds, config.validSeconds + 3600).lastInsertId();
    eventIds.push(positiveInteger(eventId, 'event ID', Number.MAX_SAFE_INTEGER));
    for (let offset = 0; offset < config.quantity; offset += BATCH_SIZE) {
      const count = Math.min(BATCH_SIZE, config.quantity - offset);
      const numbers = Array.from({ length: count }, (_, j) => `SELECT ${offset + j + 1} AS sequence_no`).join(' UNION ALL ');
      db.exec(`INSERT INTO coupon_inventory
        (event_id, sequence_no, coupon_code, status, usable_start_time, usable_end_time, created_at, updated_at)
        SELECT e.id, seq.sequence_no, RANDOM_BYTES(16), 'AVAILABLE', e.usable_start_time,
               e.usable_end_time, TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()), TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())
        FROM coupon_event e CROSS JOIN (${numbers}) seq WHERE e.id = ?`, eventId);
    }
  }
  const data = { runId, eventIds, userRanges, config };
  const events = eventCounts(db, marker);
  if (events.length !== config.eventCount
      || events.some(row => Number(row.inventory) !== config.quantity || Number(row.issued) !== 0
        || Number(row.coupons) !== 0 || Number(row.available) !== config.quantity)
      || userRanges.reduce((sum, range) => sum + range.count, 0) !== config.userCount) {
    throw new Error('Fixture preparation counts do not match');
  }
  const valid = scalar(db, `SELECT COUNT(*) n FROM coupon_event e JOIN campaign c ON c.id = e.campaign_id
    WHERE c.notice = ? AND c.status = 'ACTIVE' AND e.status = 'ACTIVE'
      AND e.issue_start_at <= TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP()) AND e.issue_end_at > TIMESTAMPADD(HOUR, 9, UTC_TIMESTAMP())`, marker);
  if (valid !== config.eventCount) throw new Error('Fixture issuance period already expired');
  return data;
}

/**
 * 한 실행의 이벤트별 총량·상태별 재고·실제 발급 수량을 조회한다.
 * @param {object} db 열린 xk6-sql 연결.
 * @param {string} marker runMarker로 검증한 캠페인 마커.
 * @returns {Array<object>} 이벤트 ID 순서의 집계 행. DB 숫자는 문자열일 수 있다.
 */
function eventCounts(db, marker) {
  return db.query(`SELECT e.id, e.coupon_quantity quantity,
    (SELECT COUNT(*) FROM coupon_inventory i WHERE i.event_id = e.id) inventory,
    (SELECT COUNT(*) FROM coupon_inventory i WHERE i.event_id = e.id AND i.status = 'AVAILABLE') available,
    (SELECT COUNT(*) FROM coupon_inventory i WHERE i.event_id = e.id AND i.status = 'ISSUED') issued,
    (SELECT COUNT(*) FROM user_coupon u WHERE u.event_id = e.id) coupons
    FROM coupon_event e JOIN campaign c ON c.id = e.campaign_id WHERE c.notice = ? ORDER BY e.id`, marker);
}

/**
 * HTTP 성공 횟수가 아닌 커밋된 발급·재고 매칭·한국 날짜별 한도를 검증한다. 데이터를 변경하지 않는다.
 * @param {object} db API와 동일한 테스트 DB의 열린 연결.
 * @param {object} data seedFixture가 반환한 실행 ID와 기대 수량 설정.
 * @returns {object} passed, issuedTotal, 오류별 건수 및 이벤트별 수량을 포함한 보고서.
 */
export function verifyFixture(db, data) {
  const marker = runMarker(data.runId);
  const events = eventCounts(db, marker).map(row => Object.fromEntries(
    Object.entries(row).map(([key, value]) => [key, Number(value)])));
  const inventoryMismatch = scalar(db, `SELECT COUNT(*) n FROM coupon_inventory i
    JOIN coupon_event e ON e.id = i.event_id JOIN campaign c ON c.id = e.campaign_id
    LEFT JOIN user_coupon u ON u.event_id = i.event_id AND u.coupon_code = i.coupon_code
    LEFT JOIN \`user\` owner ON owner.id = u.user_id
    WHERE c.notice = ? AND ((i.status = 'ISSUED' AND u.id IS NULL)
      OR (i.status = 'AVAILABLE' AND u.id IS NOT NULL)
      OR (u.id IS NOT NULL AND (u.status <> 'ISSUED' OR owner.provider_user_id NOT LIKE ?)))`, marker, marker + ':%');
  const duplicates = scalar(db, `SELECT COUNT(*) n FROM (
    SELECT u.event_id, u.user_id FROM user_coupon u JOIN coupon_event e ON e.id = u.event_id
    JOIN campaign c ON c.id = e.campaign_id WHERE c.notice = ?
    GROUP BY u.event_id, u.user_id HAVING COUNT(*) > 1) duplicated`, marker);
  const dailyMismatch = scalar(db, `SELECT COUNT(*) n FROM (
    SELECT u.user_id, DATE(u.created_at) day, COUNT(*) actual
    FROM user_coupon u JOIN \`user\` owner ON owner.id = u.user_id
    WHERE owner.provider_user_id LIKE ? GROUP BY u.user_id, day
    ) issued LEFT JOIN coupon_daily_limit d ON d.user_id = issued.user_id AND d.limit_date = issued.day
    WHERE issued.actual > 3 OR d.id IS NULL OR d.issued_count <> issued.actual`, marker + ':%');
  const extraDailyLimits = scalar(db, `SELECT COUNT(*) n FROM coupon_daily_limit d
    JOIN \`user\` owner ON owner.id = d.user_id WHERE owner.provider_user_id LIKE ?
    AND d.issued_count <> (SELECT COUNT(*) FROM user_coupon u WHERE u.user_id = d.user_id
      AND DATE(u.created_at) = d.limit_date)`, marker + ':%');
  const issuedTotal = events.reduce((sum, row) => sum + row.coupons, 0);
  const passed = events.length === data.config.eventCount && issuedTotal > 0
    && events.every(row => row.quantity === data.config.quantity && row.inventory === row.quantity
      && row.issued === row.coupons && row.issued + row.available === row.inventory
      && row.coupons <= row.quantity && (!data.config.requireSoldOut || row.coupons === row.quantity))
    && inventoryMismatch === 0 && duplicates === 0 && dailyMismatch === 0 && extraDailyLimits === 0;
  return { runId: data.runId, passed, issuedTotal, inventoryMismatch, duplicates, dailyMismatch, extraDailyLimits, events };
}

/**
 * 종료된 한 실행의 데이터만 FK 역순으로 삭제하고 잔여 여부를 검사한다. 반복 호출 가능하며 복구 불가다.
 * 교차 참조가 있으면 첫 DELETE 전에 거절한다. 호출자는 해당 실행의 API 처리가 끝났음을 보장해야 한다.
 * @param {object} db 삭제 권한이 있는 테스트 DB 연결.
 * @param {string} runId 삭제할 실행의 정확한 32자리 ID.
 * @returns {void} 삭제 완료 시 반환하며 수량은 콘솔 정리 로그로 확인한다.
 * @throws {Error} ID 형식 오류, 타 실행과 교차 참조, SQL 실패 또는 잔여 데이터가 있는 경우.
 */
export function cleanupFixture(db, runId) {
  const marker = runMarker(runId);
  const pattern = marker + ':%';
  // 다른 데이터가 테스트 사용자/이벤트를 참조하면 광범위하게 지우지 않고 중단한다.
  const crossReferences = scalar(db, `SELECT COUNT(*) n FROM user_coupon u
    JOIN coupon_event e ON e.id = u.event_id JOIN campaign c ON c.id = e.campaign_id
    JOIN \`user\` owner ON owner.id = u.user_id
    WHERE (c.notice <=> ?) <> (owner.provider_user_id LIKE ?)`, marker, pattern);
  if (crossReferences !== 0) throw new Error('Cleanup refused: non-run data references this run; inspect manually');
  db.exec(`DELETE h FROM coupon_usage_history h JOIN user_coupon u ON u.id = h.user_coupon_id
    JOIN coupon_event e ON e.id = u.event_id JOIN campaign c ON c.id = e.campaign_id WHERE c.notice = ?`, marker);
  db.exec(`DELETE u FROM user_coupon u JOIN coupon_event e ON e.id = u.event_id
    JOIN campaign c ON c.id = e.campaign_id WHERE c.notice = ?`, marker);
  db.exec(`DELETE i FROM coupon_inventory i JOIN coupon_event e ON e.id = i.event_id
    JOIN campaign c ON c.id = e.campaign_id WHERE c.notice = ?`, marker);
  db.exec(`DELETE d FROM coupon_daily_limit d JOIN \`user\` u ON u.id = d.user_id
    WHERE u.provider_user_id LIKE ?`, pattern);
  db.exec('DELETE e FROM coupon_event e JOIN campaign c ON c.id = e.campaign_id WHERE c.notice = ?', marker);
  db.exec('DELETE FROM campaign WHERE notice = ?', marker);
  db.exec('DELETE FROM `user` WHERE provider_user_id LIKE ?', pattern);
  if (scalar(db, 'SELECT COUNT(*) n FROM campaign WHERE notice = ?', marker) !== 0
      || scalar(db, 'SELECT COUNT(*) n FROM `user` WHERE provider_user_id LIKE ?', pattern) !== 0) {
    throw new Error('Cleanup incomplete');
  }
  console.log(`CLEANUP ${runId}: run data removed (not recoverable from this script)`);
}
