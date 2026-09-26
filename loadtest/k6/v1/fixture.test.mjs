import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compactUserIds, userAt, runMarker, cleanupFixture, seedFixture, positiveInteger } from './fixture.js';

test('cleanup rejects empty, partial, wildcard and injected run IDs before any SQL', () => {
  const db = { query: () => assert.fail('must not query'), exec: () => assert.fail('must not delete') };
  for (const id of ['', undefined, '%', '_', 'abc', "' OR 1=1 --", 'A'.repeat(32)]) {
    assert.throws(() => cleanupFixture(db, id), /Invalid K6_RUN_ID/);
  }
  assert.equal(runMarker('a'.repeat(32)), 'k6-coupon:' + 'a'.repeat(32));
});

test('ID compression tolerates gaps and never selects another user', () => {
  const ids = [11, 12, 15, 21, 22, 23];
  const ranges = compactUserIds(ids.map(id => ({ id })));
  assert.deepEqual(ranges, [{ start: 11, count: 2 }, { start: 15, count: 1 }, { start: 21, count: 3 }]);
  assert.deepEqual(ids.map((_, index) => userAt(ranges, index)), ids);
  assert.throws(() => userAt(ranges, 6), /outside fixture/);
  assert.throws(() => compactUserIds([{ id: '9007199254740993' }]), /user ID/);
});

test('default request sequence assigns one unique user to each of 10,000 requests', () => {
  const ranges = compactUserIds(Array.from({ length: 10000 }, (_, i) => ({ id: i + 1 })));
  const userIds = Array.from({ length: 10000 }, (_, iteration) => userAt(ranges, iteration));
  assert.equal(new Set(userIds).size, 10000);
  assert.equal(userIds[0], 1);
  assert.equal(userIds[9999], 10000);
  assert.throws(() => userAt(ranges, 10000), /outside fixture/);
  for (let event = 0; event < 10; event += 1) {
    assert.equal(userIds.filter((_, iteration) => iteration % 10 === event).length, 1000);
  }
});

test('cleanup refuses cross-run references before deleting anything', () => {
  const db = { query: () => [{ n: 1 }], exec: () => assert.fail('must not delete') };
  assert.throws(() => cleanupFixture(db, 'a'.repeat(32)), /Cleanup refused/);
});

test('fixture sizes reject zero, fractions, NaN and excessive values', () => {
  for (const value of ['x', 0, -1, 1.5, 101]) assert.throws(() => positiveInteger(value, 'size', 100));
  assert.equal(positiveInteger('100', 'size', 100), 100);
});

test('a colliding run ID is rejected before writing and can be distinguished from partial seed failure', () => {
  const db = { query: () => [{ n: 1 }], exec: () => assert.fail('must not write') };
  assert.throws(() => seedFixture(db, 'a'.repeat(32), {}), error => error.code === 'RUN_ALREADY_EXISTS');
});
