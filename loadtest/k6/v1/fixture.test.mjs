import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compactUserIds, userAt, userIndexFor, runMarker, cleanupFixture, seedFixture, positiveInteger } from './fixture.js';

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

test('default request sequence is exactly 50,000 users and 10,000 retries', () => {
  const indices = Array.from({ length: 60000 }, (_, i) => userIndexFor(i, 50000));
  assert.equal(new Set(indices).size, 50000);
  assert.equal(Math.min(...indices), 0);
  assert.equal(Math.max(...indices), 49999);
  assert.equal(userIndexFor(60000, 50000), 0);
  for (let i = 5; i < indices.length; i += 6) {
    assert.ok(indices.slice(i - 5, i).includes(indices[i]));
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
