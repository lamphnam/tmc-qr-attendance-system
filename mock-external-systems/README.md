# mock-external-systems  -  MOCK / DEV-ONLY

Two zero-dependency Node.js HTTP servers (Node >= 20) standing in for systems TMC does not own. They are deliberately **not**
Spring Boot: the team's five Java applications are the real services; mocks stay tiny.

| Mock | Owner | Port | Purpose |
|---|---|---|---|
| `mock-turnstile-api` | Khánh | 8090 | Factory-LAN turnstile controller: `POST /gates/{gateId}/open`, status, command log, failure injection |
| `mock-hr-payroll-api` | Nam | 8091 | HR master data (`GET /mock/hr/employees`) and a **batch receiver with idempotent upsert** + failure injection |

Neither proves anything physical or about a real HR product. In particular `mock-turnstile-api` answers `physicalOpenConfirmed:false`
and its latency says nothing about real hardware. Endpoint shapes are **[DRAFT]** (the turnstile is not in [API-CONTRACTS](../docs/API-CONTRACTS.md) at all; DECISIONS B18).

```bash
cd mock-external-systems/mock-hr-payroll-api && npm test         # node:test, no install needed
SIMULATE_HR_DOWN=503 docker compose up -d mock-hr-payroll-api     # start it failing (500/503/any 4xx-5xx; 0 = healthy)
curl -X PUT localhost:8091/mock/hr/control/failure -H 'Content-Type: application/json' -d '{"status":503}'   # toggle at runtime
```

HR state is written to `HR_DATA_FILE` (`/data/hr-state.json` on the `mock-hr-data` volume) so a restart can be demonstrated;
`docker compose down -v` wipes it. Both mocks have no authentication.
