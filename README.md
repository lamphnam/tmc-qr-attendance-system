# TMC QR Attendance & Access Control — M2 codebase (bootstrap)

Môn **CO3017 Software Architecture — Assignment M2**. Kiến trúc lai **Service-based + Offline-First Kiosk** kế thừa M1: 4 service Spring Boot dùng chung 1 PostgreSQL, 1 kiosk-agent với SQLite WAL, đồng bộ HTTP REST trực tiếp, **không** message broker ([docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).

> **Đây là nền code, không phải sản phẩm hoàn tất.** Mọi service build, khởi động, có health thật và nói chuyện được với nhau, nhưng chưa có đăng nhập/RBAC, TOTP, quét QR thật, báo cáo, xuất HR, audit chain. Cái gì thật và cái gì chưa: [docs/STATUS.md](docs/STATUS.md). Việc còn lại của từng người: [docs/TODO.md](docs/TODO.md). Stack này là **dev-only** (HTTP thường, không xác thực, mật khẩu giả, mô phỏng edge + central trên một máy); giới hạn cần nhắc đúng: [docs/DECISIONS.md](docs/DECISIONS.md#known-limitations).

## 1. Yêu cầu

| Công cụ | Phiên bản | Ghi chú |
|---|---|---|
| JDK | **21** | `JAVA_HOME` phải trỏ JDK 21. Kiểm tra bằng `mvn -v` (dòng *Java version*): Maven dùng `JAVA_HOME`, **không** dùng `java` trong `PATH` |
| Maven | 3.9+ | Spring Boot **3.5.16** |
| Node.js | 20.19+ (khuyến nghị 22 LTS) | frontend (Vite 7) và 2 mock |
| Docker | Engine + Compose v2 | Docker Desktop phải **đang chạy**; cũng cần cho test Testcontainers của `attendance-processor` |
| bash + curl | tuỳ chọn | `scripts/smoke.sh`; Windows có `scripts/smoke.ps1` |

## 2. Chạy nhanh

```bash
cp .env.example .env                       # PowerShell: Copy-Item .env.example .env   (không bắt buộc: compose có sẵn giá trị mặc định giống hệt)
docker compose up --build -d --wait        # build 10 container; lần đầu mất vài phút (Maven tải dependency)
bash scripts/smoke.sh                      # Windows PowerShell: powershell -ExecutionPolicy Bypass -File scripts\smoke.ps1
```

Mở **http://localhost:8080** (qua gateway). Trang *Status* hiển thị health thật của từng service; trang *Dev slice* điều khiển luồng kiosk → central. `make up`, `make smoke`... là lối tắt tương đương (cần GNU make; `make help`).

## 3. Cổng

Mọi cổng publish đều bind **`127.0.0.1`** (có endpoint dev không xác thực). Đổi bằng biến `*_HOST_PORT` trong `.env`.

| Thành phần | Trong Compose | Trên host | Kiểm tra |
|---|---|---|---|
| nginx-gateway | 80 | **8080** | `/gateway/health` |
| web-frontend | 80 | 3000 | `/` (tự proxy `/api`, `/services` về gateway) |
| user-qr, gate-station, attendance, reporting | 8080 | — | `/services/user-qr/health`, `/services/gate-station/health`, `/services/attendance/health`, `/services/reporting/health` qua gateway |
| kiosk-agent | 8080 | 8085 | `/services/kiosk/health` hoặc `localhost:8085/actuator/health` |
| mock-turnstile-api | 8090 | 8090 | `localhost:8090/health` |
| mock-hr-payroll-api | 8091 | 8091 | `localhost:8091/health` |
| central-db (PostgreSQL 16) | 5432 | **5433** | `psql -h 127.0.0.1 -p 5433 -U tmc_app tmc` |

DB ra host là 5433 (không phải 5432) vì máy dev thường đã có PostgreSQL ở 5432; trong Compose các service vẫn gọi `central-db:5432`.

## 4. Test

```bash
mvn -B test                                                   # 5 module Java (JDK 21!). attendance-processor cần Docker cho PostgreSQL thật
(cd web-frontend && npm ci && npm run typecheck && npm test && npm run build)
(cd mock-external-systems/mock-turnstile-api && npm test)
(cd mock-external-systems/mock-hr-payroll-api && npm test)
docker compose config --quiet
bash scripts/smoke.sh                                         # cần stack đang chạy
node --test tests/integration/slice.test.mjs                  # cần stack đang chạy; SKIP_DISRUPTIVE=1 để bỏ test dừng/restart container
```

**Một lệnh cho tất cả:** `node scripts/verify.mjs` chạy mọi bước trên (tự tìm JDK 21, dựng stack, smoke, integration) và in bảng kết quả trung thực (`--fast` bỏ phần Docker; log ở `.verify-logs/`). `node scripts/check-docs.mjs` kiểm tra link và độ nhất quán của markdown. `make test` chạy 3 dòng đầu. Chạy một test đơn lẻ: [CLAUDE.md](CLAUDE.md). Kết quả đã chạy thật khi dựng repo: [docs/STATUS.md](docs/STATUS.md#3-commands-run-and-results). Kế hoạch kiểm thử và tiêu chí nghiệm thu: [docs/TESTING.md](docs/TESTING.md).

## 5. Cấu trúc

```text
docker-compose.yml  .env.example  Makefile  pom.xml (aggregator, không phải parent)
init-db/            schema.sql + seed.sql — NGUỒN DUY NHẤT của schema PostgreSQL (chạy 1 lần trên volume rỗng)
nginx-gateway/      nginx.conf — định tuyến /api/v1/*, /services/*/health, /api/dev/*
backend-services/   user-qr-service (Khoa) · gate-station-service (Khôi) · attendance-processor (Khôi) · reporting-service (Nam)
kiosk-agent/        Khánh — SQLite WAL, SyncManager, adapter turnstile
mock-external-systems/  mock-turnstile-api (Khánh) · mock-hr-payroll-api (Nam)  — Node, không dependency
web-frontend/       Khiêm — React + TypeScript + Vite
tests/              integration/ (assert trên số dòng DB thật) · load/ (chưa có test đo)
scripts/            smoke.sh · smoke.ps1
docs/               tài liệu (bản đồ: docs/README.md)
```
Layout trong mỗi service và luật phụ thuộc giữa các lớp: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## 6. Thử vertical slice (dev-only, dữ liệu giả — không có QR/TOTP)

```bash
G=http://localhost:8080
curl -X PUT  $G/api/dev/kiosk/sync/offline -H 'Content-Type: application/json' -d '{"disabled":true}'   # "mất WAN": chỉ dừng kiosk→central
curl -X POST $G/api/dev/kiosk/synthetic-event                       # SQLite: PENDING (commit trước), rồi gọi mock turnstile
curl      $G/api/dev/kiosk/queue                                    # syncStatus=PENDING, gateCommandStatus=SENT
curl -X PUT  $G/api/dev/kiosk/sync/offline -H 'Content-Type: application/json' -d '{"disabled":false}'
curl -X POST $G/api/dev/kiosk/sync/run                              # central commit rồi ack → SYNCED
docker compose exec central-db psql -U tmc_app -d tmc -c "select attendance_id, employee_id, dev_fixture from attendance_events"
```
Gửi lại cùng `attendanceId` tới `POST /api/dev/attendance/events` → `ALREADY_EXISTS`, số dòng không đổi. **SYNCED chỉ là trạng thái đồng bộ về central, không chứng minh cổng đã mở.**

## 7. Dừng / reset

```bash
docker compose down        # dừng, GIỮ dữ liệu (volume central-db-data, kiosk-data, mock-hr-data)
docker compose down -v     # dừng và XOÁ dữ liệu — cần khi đổi init-db/schema.sql (chỉ chạy trên volume rỗng)
```

## 8. Sự cố thường gặp

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| `release version 21 not supported` khi `mvn` | Maven đang chạy JDK cũ. Đặt `JAVA_HOME` sang JDK 21 (PowerShell: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21...'`) rồi `mvn -v` |
| `port is already allocated` | Cổng host bị chiếm. Đổi `*_HOST_PORT` trong `.env` |
| `failed to connect to the docker API` | Docker Desktop chưa chạy |
| Test Testcontainers bị `Skipped` | Không có Docker; `Skipped` không phải `passed` — đọc số liệu surefire |
| Sửa `schema.sql` mà DB không đổi | Init chỉ chạy trên volume rỗng: `docker compose down -v` |
| Script `.sh` lỗi `\r` trên Windows | Checkout bị đổi CRLF. Repo có `.gitattributes` ép LF; chạy `git add --renormalize .` |
| Một service `unhealthy` | `docker compose logs --tail=80 <service>`; health của service Java chỉ `UP` khi PostgreSQL trả lời |

## 9. Đọc tiếp

[AGENTS.md](AGENTS.md) — luật cho agent và người đóng góp (đọc trước khi nhờ agent viết code) · [docs/README.md](docs/README.md) — bản đồ tài liệu · [docs/OWNERSHIP.md](docs/OWNERSHIP.md) — owner và quy trình PR.
