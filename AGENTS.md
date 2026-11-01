# AGENTS.md — luật bắt buộc cho coding agent (và người)

Nguồn duy nhất của các luật làm việc trong repo. Các file khác không lặp lại chúng: [CLAUDE.md](CLAUDE.md) (lệnh test đơn lẻ + sơ đồ code), [README.md](README.md) (chạy nhanh, cổng, test), [docs/README.md](docs/README.md) (bản đồ tài liệu).

## Trước khi viết code
1. Đọc [docs/STATUS.md](docs/STATUS.md): module bạn sửa là `READY`, `DEV_SLICE`, `SCAFFOLD` hay `MOCK`. Rồi đọc đúng tài liệu cho việc của bạn theo bảng "What to read for what" trong [docs/README.md](docs/README.md).
2. Chỉ làm trong module của owner được giao ([docs/OWNERSHIP.md](docs/OWNERSHIP.md)); không âm thầm làm thay người khác, không ghi đè code chưa biết, kiểm tra trạng thái repo trước khi sửa.
3. Trước một thay đổi lớn, tóm tắt cho người dùng: ranh giới kiến trúc liên quan, điều chưa chốt (DRAFT), phạm vi sẽ làm.

## Luật không được vi phạm
1. **Kiến trúc M1 cố định** ([ARCHITECTURE](docs/ARCHITECTURE.md)): 4 service domain + kiosk-agent riêng, chung một PostgreSQL, SQLite ở kiosk, REST trực tiếp. Không monolith, không DB-per-service, không Kafka/RabbitMQ/Redis/Keycloak/service mesh nếu chưa có ADR được nhóm duyệt.
2. **Bốn lớp** `presentation → application → domain ← infrastructure`: `domain` không import Spring/`jakarta`/JDBC/HTTP; port ở domain, implementation ở infrastructure; constructor injection; không import Java chéo giữa các service. `*ArchitectureTest` làm fail `mvn test` — đừng tắt nó, đổi luật phải có lý do trong PR.
3. **Kiosk (ADR-01):** commit event PENDING vào SQLite **trước** khi gửi lệnh mở cổng; `SYNCED` chỉ sau khi central đã commit và ack **đúng id đó**; `attendanceId` sinh một lần lúc quét và dùng lại cho mọi retry; `sync_status` không phải trạng thái cổng vật lý.
4. **Bí mật:** shared secret/TOTP không bao giờ tới browser. Không log token, TOTP, password, secret, JWT (DTO chứa TOTP phải redact `toString`). Không commit `.env`, `*.db`, `target/`, `node_modules/`, `dist/`, khoá; `.env.example` chỉ có giá trị giả.
5. **Contract là DRAFT** ([API-CONTRACTS](docs/API-CONTRACTS.md), [DATA-MODEL](docs/DATA-MODEL.md)). Đổi contract liên module: sửa tài liệu đó, ghi vào [DECISIONS](docs/DECISIONS.md), và cần reviewer của **cả hai phía**.
6. **Schema PostgreSQL chỉ ở `init-db/schema.sql`.** Service không tự tạo/sửa bảng, không dùng Hibernate `ddl-auto`, không DDL trùng lặp. Muốn áp dụng thay đổi: `docker compose down -v` (xoá dữ liệu dev).
7. **Không giả thành công.** Tính năng chưa làm trả `501 {"code":"NOT_IMPLEMENTED"}` — không viết mock vài chục dòng rồi gọi là TOTP/RBAC/MFA. Endpoint `/api/dev/**` chỉ chạy ở profile `dev`, không xác thực, dữ liệu giả; không biến thành đường production khi chưa thêm auth.
8. **Không tuyên bố khi chưa đo** — chính sách và danh sách: [PROJECT §5](docs/PROJECT.md#5-claims-policy-canonical--other-docs-link-here); giới hạn phải nhắc đúng: [DECISIONS — Known limitations](docs/DECISIONS.md#known-limitations).
9. **Git:** không `git init/add/commit/push`, không tạo repo, không sửa remote, không force-push thay người dùng; người dùng tự review rồi push. Không gọi API bên thứ ba hay GitHub khi chưa được yêu cầu.
10. **Báo cáo trung thực:** chạy thật, ghi lệnh + exit code. Nếu Docker/Java/Node thiếu thì ghi đúng lệnh chưa chạy được; không tự ghi PASS. `Skipped` không phải `passed` (test Testcontainers bị skip khi không có Docker).

## Khi xong việc
Cập nhật cùng lúc ba nơi khi trạng thái một module đổi: [docs/STATUS.md](docs/STATUS.md), [docs/TRACEABILITY.md](docs/TRACEABILITY.md) và `web-frontend/src/catalog.ts` (nhãn trên dashboard). Thêm test tích hợp thật vào `tests/integration/` trước khi đổi nhãn. Kiểm tra bằng bộ lệnh ở [README §4](README.md#4-test).

## Quy ước code
Java 21, Spring Boot 3.5.16, Maven; package `vn.edu.hcmut.tmc.<context>`; record cho DTO; comment chỉ để giải thích *vì sao*. Giữ tên class trong [class map](docs/TRACEABILITY.md#java-class-map) khi có nghĩa, ghi ánh xạ nếu đổi tên. Lỗi HTTP dùng `{code, message, correlationId}`; không trả stack trace và không echo request body.
