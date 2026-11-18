# M2 acceptance evidence for slides

Verified on 2026-11-18 in the isolated `tmc-priority-review` Compose project at BD. The stack ran on one
Windows machine with an Intel i5-1135G7 and 3.68 GiB assigned to the Docker VM. It used JDK 21.0.6, Docker
Engine 27.5.1, PostgreSQL 16 and k6 2.4.0. The turnstile and HR receiver were mocks.
[Verification record and commands](STATUS.md#23-m2-follow-up-and-compose-acceptance-2026-11-18).

## Results to cite

| Run | Result | Evidence or scope |
|---|---|---|
| Compose smoke | 35/35 PowerShell and 35/35 Git Bash | All 10 containers healthy, including Gateway, kiosk and Central DB routes |
| Maven reactor with Testcontainers | 680 passed, 0 skipped | Gate Station 4, User/QR 51, Attendance 126, Reporting 91, Kiosk 408 |
| Compose integration | 19 passed, 0 skipped | Includes QR → gate command → PostgreSQL → reporting and all 20 T02 IDs after restart |
| Frontend and mocks | 118 frontend, 6 turnstile mock and 11 HR mock tests passed | Frontend typecheck and build passed |
| T03 and activation | Prior QR denied after revoke and whitelist refresh. New QR accepted after activation and refresh. | Chrome headless with the deployed gateway |
| T01 steady | 801/801 allowed, 0 HTTP errors, 0 dropped, 100% within 2 s | Configured 800 scans per 30 minutes, p50 38.72 ms, p95 135.68 ms, p99 269.74 ms, max 1.84 s |
| T01 burst | 801/801 allowed, 0 HTTP errors, 0 dropped | Configured 800 scans per 2 minutes, p95 60.89 ms, p99 152.07 ms, max 915.06 ms |
| T06 BD/date report | 25 measured requests over 35,200 events for 800 employees | Page of 500 rows, p50 753.29 ms, p95 1,877.21 ms, p99/max 2,693.95 ms |
| NFR-6 cutoff | Scheduled batch found after cutoff became `FAILED` with 0 attempts and deadline 06:00 VN | PostgreSQL record plus fake-clock tests. Manual rearm is available. |

## Log excerpts

These lines omit credentials, raw QR payloads and local machine paths. Full run commands, exit codes and the
initial User/QR JDBC timeout and overlapping quick-load failure are recorded in [STATUS §23](STATUS.md#23-m2-follow-up-and-compose-acceptance-2026-11-18).

```text
SMOKE PASSED: 35 checks, 0 failed.
scripts/smoke.ps1 exit 0
SMOKE PASSED: 35 checks, 0 failed.
bash scripts/smoke.sh exit 0

PASS    integration  exit 0; 19 of 19 passed, 0 skipped
ok 6 - T02: 20 real scans survive an offline restart and sync each ID once including a prior central commit

T01 steady, scan requests only:
scan_allowed...................: 100.00% 801 out of 801
scan_within_2s.................: 100.00% 801 out of 801
p(95)=135.68ms p(99)=269.74ms max=1.84s count=801
dropped_iterations.............: 0

Scheduled cutoff row (period | trigger | status | attempts | deadline VN):
2026-10|SCHEDULED|FAILED|0|2026-11-01 06:00:00
```

In the steady time range PostgreSQL stored 802 events, 802 distinct attendance IDs and 802 distinct
employees, including the setup probe. Compose T02 reproduced Central already having committed while the kiosk
still lacked an ack. A separate local test physically dropped the HTTP response from a Central stub.

## Slide assets and sign-off scope

- [Chrome screenshot after revocation](evidence/m2-demo-revoked.png)
- [Chrome screenshot after activation](evidence/m2-demo-activated.png)
- [M2 review PDF](evidence/TMC-M2-Review.pdf) and [editable Markdown](M2-REVIEW.md)

The screenshots show synthetic employees with the QR image hidden. `SENT` means the mock received the
command. T01 covers BD on the reviewed development setup. T06 covers the date/factory route; report shifts
and permission checks were outside that measurement. B31–B33 remain owner review items, and employee IDs
of 33–36 characters require a cross-service contract decision. [Open items](TODO.md).
