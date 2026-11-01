// PLACEHOLDER (NOT_TESTED). Shows where k6 scripts live; it is NOT an NFR-1 / NFR-7 test.
// Replace with the real scan / report scenarios once those endpoints exist.
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 2,
  duration: '10s',
  thresholds: { http_req_failed: ['rate<0.01'] },
};

const GATEWAY = __ENV.GATEWAY_URL || 'http://127.0.0.1:8080';

export default function () {
  const res = http.get(`${GATEWAY}/gateway/health`);
  check(res, { 'gateway answers 200': (r) => r.status === 200 });
}
