import type { ApiError } from '../../api/client';
import { summarize, type CentralRow } from '../../demo/central';
import { formatClock, shortId } from '../../demo/format';
import ApiErrorNotice, { ErrorIcon } from './ApiErrorNotice';
import Badge from './Badge';
import type { KioskMessage } from './KioskColumn';

export interface CentralColumnProps {
  source: 'list' | 'lookup';
  rows: readonly CentralRow[];
  loaded: boolean;
  error: ApiError | null;
  resendBusy: boolean;
  canResend: boolean;
  onResend: () => void;
  resendMessage: KioskMessage;
}

const EMPTY_TEXT = {
  list: 'PostgreSQL chưa có bản ghi nào.',
  lookup: 'Chưa có bản ghi nào ở kiosk để đối chiếu.',
} as const;

function PresenceCell({ row }: { row: CentralRow }) {
  if (row.presence === 'PRESENT') return <Badge tone="ok">Có</Badge>;
  if (row.presence === 'ABSENT') return <Badge tone="warn">Chưa có</Badge>;
  return (
    <div className="tmc-col-tight">
      <Badge tone="neutral">Không rõ</Badge>
      {row.error && <span className="tmc-small">{row.error}</span>}
    </div>
  );
}

export default function CentralColumn(props: CentralColumnProps) {
  const { source, rows, loaded, error, resendBusy, canResend, onResend, resendMessage } = props;
  const summary = summarize(rows);
  return (
    <section className="tmc-card tmc-col" aria-labelledby="demo-col-central">
      <div className="demo-colhead">
        <span className="demo-step" aria-hidden="true">
          3
        </span>
        <h2 id="demo-col-central" className="tmc-h2">
          Trung tâm: PostgreSQL
        </h2>
      </div>
      {loaded && (
        <div className="tmc-btnrow">
          <Badge tone="ok">{`Có trong PostgreSQL: ${summary.present}`}</Badge>
          <Badge tone="warn">{`Chưa có: ${summary.absent}`}</Badge>
          {summary.unknown > 0 && <Badge tone="neutral">{`Không rõ: ${summary.unknown}`}</Badge>}
        </div>
      )}
      {summary.syncedButAbsent.length > 0 && (
        <div className="tmc-error" role="alert">
          <ErrorIcon />
          <p className="tmc-error-title">
            {`Kiosk ghi SYNCED nhưng trung tâm không có: ${summary.syncedButAbsent.map(shortId).join(', ')}`}
          </p>
        </div>
      )}
      {error && <ApiErrorNotice error={error} />}
      {!loaded ? (
        <div className="tmc-skel" aria-hidden="true">
          <div className="tmc-skel-line" />
          <div className="tmc-skel-line" />
        </div>
      ) : (
        (rows.length > 0 || error === null) && (
          <div className="tmc-table-wrap">
            <table className="tmc-table">
              <thead>
                <tr>
                  <th scope="col">Mã lượt · giờ</th>
                  <th scope="col">Nhân viên · cổng</th>
                  <th scope="col">Kiosk</th>
                  <th scope="col">Trung tâm</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.attendanceId}>
                    <td data-label="Mã lượt · giờ">
                      <div className="demo-cell2">
                        <span className="tmc-mono-id">{shortId(row.attendanceId)}</span>
                        {row.event && <span className="tmc-small">{formatClock(row.event.occurredAt)}</span>}
                      </div>
                    </td>
                    <td data-label="Nhân viên · cổng">
                      {row.event ? (
                        <div className="demo-cell2">
                          <span>{row.event.employeeId}</span>
                          <span className="tmc-small">{`${row.event.gateId} ${row.event.direction}`}</span>
                        </div>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td data-label="Kiosk">{row.kioskSyncStatus ? <Badge>{row.kioskSyncStatus}</Badge> : '—'}</td>
                    <td data-label="Trung tâm">
                      <PresenceCell row={row} />
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={4} className="tmc-table-empty">
                      {EMPTY_TEXT[source]}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )
      )}
      <div>
        <button
          type="button"
          className="tmc-btn tmc-btn-secondary"
          disabled={!canResend || resendBusy}
          aria-busy={resendBusy}
          onClick={onResend}
        >
          {resendBusy ? 'Đang gửi lại…' : 'Gửi lại các bản ghi SYNCED (kiểm tra chống trùng)'}
        </button>
      </div>
      {resendMessage.text && (
        <p className="tmc-small" role="status">
          {resendMessage.text}
        </p>
      )}
      {resendMessage.error && <ApiErrorNotice error={resendMessage.error} />}
    </section>
  );
}
