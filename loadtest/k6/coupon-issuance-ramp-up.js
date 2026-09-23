import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const maxRps = Number(__ENV.K6_MAX_RPS || 2000);
const rampDuration = __ENV.K6_RAMP_DURATION || '60s';
const preAllocatedVUs = Number(__ENV.K6_PRE_ALLOCATED_VUS || 1000);
const maxVUs = Number(__ENV.K6_MAX_VUS || 3000);
const fixtureWaitSeconds = Number(__ENV.K6_FIXTURE_WAIT_SECONDS || 60);
const testId = __ENV.K6_TEST_ID || 'coupon-v1';

const userIdStart = 10_000_000;
const totalScenarioRequests = 60_000;
const requestsPerRetryGroup = 6;

const successfulResponse = new Counter('coupon_issue_success_response');
const soldOut = new Counter('coupon_issue_sold_out');
const inventoryBusy = new Counter('coupon_issue_inventory_busy');
const dailyLimitRejected = new Counter('coupon_issue_daily_limit_rejected');
const unexpectedResponse = new Counter('coupon_issue_unexpected_response');

// setup() uses HTTP 200 while issuance treats 201 and business-level 409
// (sold out / daily limit) as expected outcomes.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  scenarios: {
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
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
    coupon_issue_unexpected_response: ['count==0'],
  },
  tags: {
    testid: testId,
  },
};

export function setup() {
  awaitFixtureReady();

  const response = http.get(`${baseUrl}/internal/load-test/coupon-events`);
  if (response.status !== 200) {
    throw new Error(`Load-test fixture endpoint failed with HTTP ${response.status}`);
  }

  const events = response.json();
  if (!Array.isArray(events) || events.length !== 30) {
    throw new Error(`Expected 30 active load-test events, but received ${JSON.stringify(events)}`);
  }
  return events;
}

function awaitFixtureReady() {
  for (let second = 0; second < fixtureWaitSeconds; second += 1) {
    const response = http.get(`${baseUrl}/internal/load-test/fixture`);
    if (response.status === 200 && response.json('ready') === true) {
      return;
    }
    sleep(1);
  }

  throw new Error(
    `Fixture was not ready within ${fixtureWaitSeconds}s. `
      + 'Expected 30 events, 15,000 AVAILABLE inventory rows, and no issued coupons.',
  );
}

export default function (events) {
  const userId = userIdFor(exec.scenario.iterationInTest);
  const event = events[(userId - userIdStart) % events.length];
  const response = http.post(
    `${baseUrl}/api/v1/coupon-events/${event.id}/coupons`,
    JSON.stringify({ userId }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'issue_coupon' },
    },
  );

  classify(response);
}

function userIdFor(iteration) {
  const normalizedIteration = iteration % totalScenarioRequests;
  const group = Math.floor(normalizedIteration / requestsPerRetryGroup);
  const position = normalizedIteration % requestsPerRetryGroup;

  // Five new users followed by one request for one of those users:
  // 50,000 unique users + 10,000 retries = 60,000 requests.
  return userIdStart + group * 5 + (position === 5 ? group % 5 : position);
}

function classify(response) {
  if (response.status === 201) {
    successfulResponse.add(1);
    return;
  }

  if (response.status === 409) {
    const errorCode = response.json('code');
    if (errorCode === 'COUPON_SOLD_OUT') {
      soldOut.add(1);
      return;
    }
    if (errorCode === 'INVENTORY_BUSY') {
      inventoryBusy.add(1);
      return;
    }
    if (errorCode === 'DAILY_ISSUANCE_LIMIT_EXCEEDED') {
      dailyLimitRejected.add(1);
      return;
    }
  }

  unexpectedResponse.add(1);
  check(response, {
    'has an expected coupon issuance response': (result) => result.status === 201 || result.status === 409,
  });
}
