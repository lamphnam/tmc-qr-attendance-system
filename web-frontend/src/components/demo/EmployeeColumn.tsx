import { QRCodeSVG } from 'qrcode.react';
import type { ApiError } from '../../api/client';
import type { DemoEmployee } from '../../demo/config';
import { secondsLeft, type QrCode, type QrState } from '../../demo/qr';
import ApiErrorNotice from './ApiErrorNotice';

export interface ActionState {
  busy: 'rotate' | 'revoke' | 'activate' | null;
  message: string | null;
  error: ApiError | null;
}

export interface EmployeeColumnProps {
  employees: readonly DemoEmployee[];
  selectedId: string;
  onSelect: (id: string) => void;
  qr: QrState;
  nowMs: number;
  fetching: boolean;
  onRetry: () => void;
  action: ActionState;
  onRotate: () => void;
  onRevoke: () => void;
  onActivate: () => void;
  onScanRevoked?: () => void;
}

function CodeView({ code, nowMs }: { code: QrCode; nowMs: number }) {
  const left = secondsLeft(code, nowMs);
  return (
    <div className="demo-qrbox">
      <div className="tmc-qrplate" role="img" aria-label="Mã QR vào cổng hiện tại">
        <QRCodeSVG value={JSON.stringify(code.payload)} level="M" marginSize={0} fgColor="#141414" bgColor="#ffffff" aria-hidden="true" />
      </div>
      <div className="tmc-between">
        <span className="tmc-small">Mã mới sau</span>
        <strong>{`${left} giây`}</strong>
      </div>
      <div
        className="tmc-progress"
        role="progressbar"
        aria-label="Thời gian đến mã tiếp theo"
        aria-valuemin={0}
        aria-valuemax={code.periodSeconds}
        aria-valuenow={left}
      >
        <div className="tmc-progress-bar" style={{ transform: `scaleX(${Math.min(1, left / code.periodSeconds)})` }} />
      </div>
    </div>
  );
}

function QrArea({ qr, nowMs, fetching, onRetry }: Pick<EmployeeColumnProps, 'qr' | 'nowMs' | 'fetching' | 'onRetry'>) {
  switch (qr.phase) {
    case 'loading':
      return (
        <div className="tmc-col-tight">
          <div className="tmc-skel" aria-hidden="true">
            <div className="tmc-skel-line" />
            <div className="tmc-skel-line" />
            <div className="tmc-skel-line" />
          </div>
          <p className="tmc-small">Đang lấy mã…</p>
        </div>
      );
    case 'ready':
      return <CodeView code={qr.code} nowMs={nowMs} />;
    case 'stale':
      return (
        <>
          <CodeView code={qr.code} nowMs={nowMs} />
          <div className="tmc-notice tmc-notice-warn">
            <p className="tmc-notice-body">{`Không lấy được mã mới. Mã hiện tại hết hạn sau ${secondsLeft(qr.code, nowMs)} giây.`}</p>
          </div>
          <ApiErrorNotice error={qr.error} />
        </>
      );
    case 'expired':
      return (
        <>
          <div className="tmc-qrempty">
            <p className="tmc-h3">Không có mã hợp lệ</p>
            {fetching && <p className="tmc-small">Đang lấy mã mới…</p>}
          </div>
          {qr.error && <ApiErrorNotice error={qr.error} />}
        </>
      );
    case 'locked':
      return (
        <div className="tmc-qrempty">
          <p className="tmc-h3">Mã QR của nhân viên này đã bị khóa. Liên hệ phòng Nhân sự.</p>
        </div>
      );
    case 'error':
      return <ApiErrorNotice error={qr.error} onRetry={onRetry} />;
  }
}

export default function EmployeeColumn(props: EmployeeColumnProps) {
  const { employees, selectedId, onSelect, action, onRotate, onRevoke } = props;
  return (
    <section className="tmc-card tmc-col" aria-labelledby="demo-col-employee">
      <div className="demo-colhead">
        <span className="demo-step" aria-hidden="true">
          1
        </span>
        <h2 id="demo-col-employee" className="tmc-h2">
          Nhân viên: mã QR động
        </h2>
      </div>
      <div className="tmc-field">
        <label className="tmc-label" htmlFor="demo-emp">
          Nhân viên
        </label>
        <select
          id="demo-emp"
          className="tmc-select"
          value={selectedId}
          disabled={action.busy !== null}
          onChange={(e) => onSelect(e.target.value)}
        >
          {employees.map((e) => (
            <option key={e.id} value={e.id}>{`${e.id} · ${e.name}`}</option>
          ))}
        </select>
      </div>
      <QrArea qr={props.qr} nowMs={props.nowMs} fetching={props.fetching} onRetry={props.onRetry} />
      <div className="tmc-btnrow">
        <button
          type="button"
          className="tmc-btn tmc-btn-secondary"
          disabled={action.busy !== null}
          aria-busy={action.busy === 'rotate'}
          onClick={onRotate}
        >
          {action.busy === 'rotate' ? 'Đang đổi khóa…' : 'Đổi khóa bí mật (vô hiệu mã cũ)'}
        </button>
        <button
          type="button"
          className="tmc-btn tmc-btn-danger"
          disabled={action.busy !== null}
          aria-busy={action.busy === 'revoke'}
          onClick={onRevoke}
        >
          {action.busy === 'revoke' ? 'Đang khóa…' : 'Khóa QR nhân viên nghỉ việc (HR)'}
        </button>
        {props.qr.phase === 'locked' && (
          <button type="button" className="tmc-btn tmc-btn-secondary" disabled={action.busy !== null}
            onClick={props.onActivate}>
            {action.busy === 'activate' ? 'Đang mở khóa…' : 'Mở khóa QR'}
          </button>
        )}
        {props.onScanRevoked && (
          <button type="button" className="tmc-btn tmc-btn-secondary" onClick={props.onScanRevoked}>
            Thử quét mã trước khi khóa
          </button>
        )}
      </div>
      {action.message && (
        <p className="tmc-small" role="status">
          {action.message}
        </p>
      )}
      {action.error && <ApiErrorNotice error={action.error} />}
    </section>
  );
}
