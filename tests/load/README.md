# tests/load — NOT_IMPLEMENTED

Owner: Nam (with Khánh for the kiosk side). Nothing here measures NFR-1 or NFR-7 yet. What must be measured, with workloads, thresholds and rules: [docs/TESTING.md](../../docs/TESTING.md) T01 and T06.

Pick one tool (k6 / JMeter / Locust). `k6/health-baseline.js` is a placeholder showing the layout; it was **not executed** (k6 is not installed on the bootstrap machine) and it hits a health endpoint, not the scan path, so its numbers say nothing about NFR-1.

```bash
k6 run tests/load/k6/health-baseline.js        # NOT_TESTED
```
