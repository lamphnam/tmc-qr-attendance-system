import type { ApiError } from '../../api/client';
import type { QueueSnapshot } from '../../api/types';
import { KIOSK_ID, type DemoGate } from '../../demo/config';
import { formatClock, shortId } from '../../demo/format';
import type { ScanView } from '../../demo/scan';
import ApiErrorNotice from './ApiErrorNotice';
import Badge from './Badge';

export interface KioskMessage {
  text: string | null;
  error: ApiError | null;
}

export type KioskBusy = 'offline' | 'sync' | 'whitelist' | 'synthetic' | null;

export interface KioskColumnProps {
  gates: readonly DemoGate[];
  gateId: string;
  onGate: (id: string) => void;
  canScan: boolean;
  scanning: boolean;
  onScan: () => void;
  scanView: ScanView | null;
  offline: boolean | null;
  onToggleOffline: () => void;
  busy: KioskBusy;
  onSyncNow: () => void;
  onSyncWhitelist: () => void;
  onSynthetic: () => void;
  message: KioskMessage;
  queue: QueueSnapshot | null;
  queueError: ApiError | null;
}

function ScanResult({ view }: { view: ScanView | null }) {
  if (view === null) {
    return (
      <div className="demo-result demo-result-ready">
        <p className="tmc-h3">Chưa có lượt quét</p>
      </div>
    );
  }
  const facts = [
    view.attendanceId ? `Mã lượt ${shortId(view.attendanceId)}` : null,
    view.kioskMs !== null ? `Kiosk xử lý ${view.kioskMs} ms` : null,
    `Khứ hồi ${view.roundTripMs} ms`,
    view.correlationId ? `Mã tham chiếu ${view.correlationId}` : null,
  ].filter((fact): fact is string => fact !== null);
  return (
    <div className={`demo-result demo-result-${view.tone}`}>
      <p className="demo-result-msg">{view.title}</p>
      {(view.authorization || view.gateCommand) && (
        <div className="tmc-outcomes">
          <div className="tmc-outcome">
            <p className="tmc-outcome-label">Xác thực</p>
            {view.authorization ? (
              <Badge className="tmc-outcome-value">{view.authorization}</Badge>
            ) : (
              <span className="tmc-outcome-none">—</span>
            )}
          </div>
          <div className="tmc-outcome">
            <p className="tmc-outcome-label">Lệnh tới cổng</p>
            {view.gateCommand ? (
              <Badge className="tmc-outcome-value">{view.gateCommand}</Badge>
            ) : (
              <span className="tmc-outcome-none">—</span>
            )}
          </div>
        </div>
      )}
      <p className="tmc-p">{view.detail}</p>
      <p className="tmc-small">{facts.join(' · ')}</p>
    </div>
  );
}

function QueueTable({ queue }: { queue: QueueSnapshot }) {
  const { pending, synced, failed } = queue.counts;
  return (
    <>
      <p className="tmc-small">{`PENDING ${pending} · SYNCED ${synced} · FAILED ${failed}`}</p>
      <div className="tmc-table-wrap">
        <table className="tmc-table">
          <thead>
            <tr>
              <th scope="col">Giờ</th>
              <th scope="col">Nhân viên · cổng</th>
              <th scope="col">Đồng bộ</th>
              <th scope="col">Lệnh cổng</th>
              <th scope="col">Lần thử</th>
            </tr>
          </thead>
          <tbody>
            {queue.events.map((e) => (
              <tr key={e.attendanceId}>
                <td data-label="Giờ" className="tmc-cell-mono">
                  {formatClock(e.occurredAt)}
                </td>
                <td data-label="Nhân viên · cổng">
                  <div className="demo-cell2">
                    <span>{e.employeeId}</span>
                    <span className="tmc-small">{`${e.gateId} ${e.direction}`}</span>
                  </div>
                </td>
                <td data-label="Đồng bộ">
                  <Badge>{e.syncStatus}</Badge>
                </td>
                <td data-label="Lệnh cổng">
                  <Badge>{e.gateCommandStatus}</Badge>
                </td>
                <td data-label="Lần thử">
                  <div className="demo-cell2">
                    <span>{e.retryCount}</span>
                    {e.lastError && <span className="tmc-small">{e.lastError}</span>}
                  </div>
                </td>
              </tr>
            ))}
            {queue.events.length === 0 && (
              <tr>
                <td colSpan={5} className="tmc-table-empty">
                  Chưa có bản ghi nào.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

export default function KioskColumn(props: KioskColumnProps) {
  const { gates, gateId, onGate, canScan, scanning, onScan, scanView, offline, busy, message, queue, queueError } = props;
  return (
    <section className="tmc-card tmc-col" aria-labelledby="demo-col-kiosk">
      <div className="demo-colhead">
        <span className="demo-step" aria-hidden="true">
          2
        </span>
        <h2 id="demo-col-kiosk" className="tmc-h2">{`Kiosk ${KIOSK_ID} tại cổng`}</h2>
      </div>
      <div className="tmc-field">
        <label className="tmc-label" htmlFor="demo-gate">
          Cổng quét
        </label>
        <select id="demo-gate" className="tmc-select" value={gateId} onChange={(e) => onGate(e.target.value)}>
          {gates.map((g) => (
            <option key={g.id} value={g.id}>
              {g.label}
            </option>
          ))}
        </select>
      </div>
      <div className="tmc-col-tight">
        <button
          type="button"
          className="tmc-btn tmc-btn-primary"
          disabled={!canScan || scanning}
          aria-busy={scanning}
          onClick={onScan}
        >
          {scanning ? 'Đang quét…' : 'Quét mã hiện tại vào cổng'}
        </button>
      </div>
      <div aria-live="assertive" aria-atomic="true">
        <ScanResult view={scanView} />
      </div>
      <hr className="tmc-hr" />
      <div className="demo-switch">
        <button
          type="button"
          className="k-sw"
          role="switch"
          aria-checked={offline === false}
          aria-label="Kết nối tới máy chủ trung tâm"
          disabled={offline === null || busy !== null}
          onClick={props.onToggleOffline}
        />
        <strong>{offline === null ? 'Không rõ' : offline ? 'Ngoại tuyến' : 'Trực tuyến'}</strong>
      </div>
      {offline === true && (
        <div>
          <Badge tone="warn" className="tmc-badge-wrap">
            Mất kết nối – lượt quét được lưu và gửi sau
          </Badge>
        </div>
      )}
      <div className="tmc-btnrow">
        <button
          type="button"
          className="tmc-btn tmc-btn-secondary"
          disabled={busy !== null}
          aria-busy={busy === 'sync'}
          onClick={props.onSyncNow}
        >
          {busy === 'sync' ? 'Đang đồng bộ…' : 'Đồng bộ sự kiện ngay'}
        </button>
        <button
          type="button"
          className="tmc-btn tmc-btn-secondary"
          disabled={busy !== null}
          aria-busy={busy === 'whitelist'}
          onClick={props.onSyncWhitelist}
        >
          {busy === 'whitelist' ? 'Đang đồng bộ whitelist…' : 'Đồng bộ whitelist'}
        </button>
        <button
          type="button"
          className="tmc-btn tmc-btn-quiet"
          disabled={busy !== null}
          aria-busy={busy === 'synthetic'}
          onClick={props.onSynthetic}
        >
          {busy === 'synthetic' ? 'Đang thêm…' : '+ Thêm sự kiện mẫu (DEV)'}
        </button>
      </div>
      {message.text && (
        <p className="tmc-small" role="status">
          {message.text}
        </p>
      )}
      {message.error && <ApiErrorNotice error={message.error} />}
      <h3 className="tmc-h3">Hàng đợi SQLite của kiosk</h3>
      {queueError && <ApiErrorNotice error={queueError} />}
      {queue === null && !queueError && (
        <div className="tmc-skel" aria-hidden="true">
          <div className="tmc-skel-line" />
          <div className="tmc-skel-line" />
        </div>
      )}
      {queue && <QueueTable queue={queue} />}
    </section>
  );
}
