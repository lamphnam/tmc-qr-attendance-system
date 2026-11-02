# AGENTS.md — luật bắt buộc cho coding agent (và người)

Nguồn duy nhất của các luật làm việc trong repo. Các file khác không lặp lại chúng: [CLAUDE.md](CLAUDE.md) (lệnh test đơn lẻ + sơ đồ code), [README.md](README.md) (chạy nhanh, cổng, test), [docs/README.md](docs/README.md) (bản đồ tài liệu), [docs/SOLID-GUIDELINES.md](docs/SOLID-GUIDELINES.md) (lý giải đầy đủ Pragmatic SOLID và bảng bằng chứng).

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

## Pragmatic SOLID
M2 chấm cả việc áp dụng SOLID trong code thật, nên SOLID là hướng thiết kế chứ không phải chỉ tiêu. Ví dụ sát TMC, anti-pattern, câu hỏi phản biện và bảng bằng chứng: [docs/SOLID-GUIDELINES.md](docs/SOLID-GUIDELINES.md).

**Quy tắc chính.** Khi thiết kế, cài đặt hoặc refactor production code, cân nhắc SOLID ở những chỗ nó mang lại lợi ích cụ thể về bảo trì, mở rộng, kiểm thử, thay thế implementation hoặc tách trách nhiệm. Không thêm abstraction, interface, cây kế thừa, design pattern hay class chỉ để "thể hiện" SOLID. Chọn thiết kế đơn giản nhất vẫn đúng với yêu cầu hiện tại và chừa điểm mở rộng hợp lý. Số interface/class hay số nguyên tắc xuất hiện không phải thước đo chất lượng kiến trúc.

1. **Khi nên cân nhắc:** một class ôm nhiều trách nhiệm có lý do đổi khác nhau; business logic gọi thẳng DB/HTTP/thiết bị; có nhiều implementation thật sự cần hoán đổi; nghiệp vụ mở rộng bằng strategy/policy/adapter; code khó unit test vì dính infrastructure; một thay đổi buộc sửa nhiều chỗ không liên quan. **Khi không cần:** trách nhiệm đã đơn giản và rõ; abstraction chỉ thêm một tầng trung gian; chưa có nhu cầu thay thế có cơ sở; refactor chỉ làm tăng số file.
2. **Abstraction mới phải có lý do kỹ thuật nói được trong một câu:** giữ chi tiết dễ đổi (PostgreSQL, SQLite, HTTP, thiết bị) ra khỏi `domain`/`application`, hoặc có từ hai implementation, hoặc thay phần chậm/không tất định (mạng, đồng hồ) trong test. Không thì dùng class cụ thể. Hợp đồng của port (precondition, postcondition, lỗi, side effect) ghi trong Javadoc của port.
3. **OCP và LSP bị hỏi sâu nhất.** Áp dụng OCP phải trả lời được: phần nào ổn định, điểm mở rộng ở đâu, thêm implementation mới phải sửa class nào, có thật sự khỏi sửa core không, lợi gì so với gọi trực tiếp. Với LSP, cùng `implements` không phải bằng chứng: kiểm tra hành vi thay thế theo hợp đồng; không trả thành công giả hay `UNSUPPORTED` tuỳ tiện; có từ hai implementation (hoặc một fake trung thực) thì viết contract test.
4. **DIP không phải DI.** Constructor injection chưa đủ: port thuộc domain, implementation ở infrastructure, `*ArchitectureTest` giữ hướng phụ thuộc.
5. **Không refactor code đang chạy chỉ để thêm nguyên tắc**, và không tạo class rỗng cho đủ tên trong design tree ([TRACEABILITY](docs/TRACEABILITY.md#java-class-map)).
6. **Không tuyên bố đã áp dụng SOLID chỉ vì tên class, tên interface hay annotation.** Không chèn comment `// SOLID`, `// OCP`, `// LSP`; code rõ nghĩa cộng tài liệu kiến trúc riêng là đủ.
7. **Xong task có thay đổi kiến trúc đáng kể** (port hoặc adapter mới, tách/gộp class lớn): nêu ngắn gọn nguyên tắc nào, ở file nào, vì sao, và giới hạn/chi phí (bảy câu hỏi ở [SOLID-GUIDELINES §3](docs/SOLID-GUIDELINES.md#3-making-it-defensible)). Task đơn giản thì không viết giải thích cho có.
8. **Bảng bằng chứng** ([SOLID-GUIDELINES §5](docs/SOLID-GUIDELINES.md#5-solid-evidence)) chỉ sửa khi có implementation liên quan, bằng nhãn `DESIGNED`/`IMPLEMENTED`/`TESTED`/`LIMITATION` ([PROJECT §5](docs/PROJECT.md#5-claims-policy-canonical--other-docs-link-here)). Chỉ ghi `IMPLEMENTED` sau khi đã đọc code thật, `TESTED` sau khi đã chạy test.

## Khi xong việc
Cập nhật cùng lúc ba nơi khi trạng thái một module đổi: [docs/STATUS.md](docs/STATUS.md), [docs/TRACEABILITY.md](docs/TRACEABILITY.md) và `web-frontend/src/catalog.ts` (nhãn trên dashboard). Thêm test tích hợp thật vào `tests/integration/` trước khi đổi nhãn. Kiểm tra bằng bộ lệnh ở [README §4](README.md#4-test).

## Quy ước code
Java 21, Spring Boot 3.5.16, Maven; package `vn.edu.hcmut.tmc.<context>`; record cho DTO; comment chỉ để giải thích *vì sao*. Giữ tên class trong [class map](docs/TRACEABILITY.md#java-class-map) khi có nghĩa, ghi ánh xạ nếu đổi tên. Lỗi HTTP dùng `{code, message, correlationId}`; không trả stack trace và không echo request body.
