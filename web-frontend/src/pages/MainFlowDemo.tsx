import { useCallback, useEffect, useRef, useState } from 'react';
import { isMissingRoute, type ApiError, type ApiResult } from '../api/client';
import * as api from '../api/endpoints';
import type { QueueSnapshot } from '../api/types';
import CentralColumn from '../components/demo/CentralColumn';
import EmployeeColumn, { type ActionState } from '../components/demo/EmployeeColumn';
import KioskColumn, { type KioskBusy, type KioskMessage } from '../components/demo/KioskColumn';
import {
  buildResendBody,
  joinWithQueue,
  parseCentralList,
  presenceFrom,
  rowsFromList,
  summarizeResend,
  type CentralRow,
} from '../demo/central';
import { DEMO_EMPLOYEES, DEMO_GATES, KIOSK_ID } from '../demo/config';
import { formatSyncReport } from '../demo/kiosk';
import { needsRefresh, onFetched, onTick, secondsLeft, type QrCode, type QrState } from '../demo/qr';
import { describeScan, describeScanError, parseScanResponse, type ScanView } from '../demo/scan';
import { usePolling } from '../demo/usePolling';

const POLL_MS = 3000;
const QUEUE_LIMIT = 15;
const CENTRAL_LIST_LIMIT = 20;
const IDLE_ACTION: ActionState = { busy: null, message: null, error: null };
const NO_MESSAGE: KioskMessage = { text: null, error: null };

export default function MainFlowDemo() {
  const [employeeId, setEmployeeId] = useState(DEMO_EMPLOYEES[0].id);
  const [qr, setQr] = useState<QrState>({ phase: 'loading' });
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [qrFetching, setQrFetching] = useState(false);
  const [action, setAction] = useState<ActionState>(IDLE_ACTION);
  const [revokedCode, setRevokedCode] = useState<QrCode | null>(null);
  const qrSeq = useRef(0);
  const lastQrAttempt = useRef(0);

  const loadQr = useCallback(async (id: string) => {
    const seq = ++qrSeq.current;
    lastQrAttempt.current = Date.now();
    setQrFetching(true);
    const result = await api.getQr(id);
    if (seq !== qrSeq.current) return;
    setQrFetching(false);
    setQr((prev) => onFetched(prev, result, Date.now()));
  }, []);

  useEffect(() => {
    setQr({ phase: 'loading' });
    setAction(IDLE_ACTION);
    setRevokedCode(null);
    void loadQr(employeeId);
  }, [employeeId, loadQr]);

  useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const previous = document.title;
    document.title = 'Luồng chính · TMC Attendance';
    return () => {
      document.title = previous;
    };
  }, []);

  useEffect(() => {
    setQr((prev) => onTick(prev, nowMs));
  }, [nowMs]);

  useEffect(() => {
    if (!qrFetching && needsRefresh(qr, nowMs, lastQrAttempt.current)) void loadQr(employeeId);
  }, [qr, nowMs, qrFetching, employeeId, loadQr]);

  useEffect(() => {
    if (revokedCode && nowMs >= revokedCode.expiresAtMs + revokedCode.periodSeconds * 1000) setRevokedCode(null);
  }, [revokedCode, nowMs]);

  const runAction = async (kind: NonNullable<ActionState['busy']>) => {
    if (kind === 'revoke' && !window.confirm(`Khóa QR của ${employeeId}?`)) return;
    const previous = kind === 'revoke' ? liveCode : null;
    setAction({ busy: kind, message: null, error: null });
    const actions = {
      rotate: () => api.rotateQr(employeeId),
      revoke: () => api.revokeQr(employeeId, 'Demo M2: nhân viên nghỉ việc (FR-1.4)'),
      activate: () => api.activateQr(employeeId),
    };
    const r = await actions[kind]();
    if (!r.ok) {
      setAction({ busy: null, message: null, error: r.error });
      return;
    }
    setRevokedCode(previous);
    const messages = {
      rotate: 'Đã đổi khóa bí mật. Kiosk ngừng chấp nhận mã cũ sau lần đồng bộ whitelist tiếp theo.',
      revoke: 'Đã khóa QR. Đồng bộ whitelist rồi thử quét mã trước khi khóa.',
      activate: 'Đã mở khóa QR. Đồng bộ whitelist để kiosk nhận phiên bản mới.',
    };
    setAction({
      busy: null,
      error: null,
      message: messages[kind],
    });
    void loadQr(employeeId);
  };

  const [gateId, setGateId] = useState(DEMO_GATES[0].id);
  const [scanning, setScanning] = useState(false);
  const [scanView, setScanView] = useState<ScanView | null>(null);
  const [offline, setOffline] = useState<boolean | null>(null);
  const [kioskBusy, setKioskBusy] = useState<KioskBusy>(null);
  const [kioskMessage, setKioskMessage] = useState<KioskMessage>(NO_MESSAGE);
  const [queue, setQueue] = useState<QueueSnapshot | null>(null);
  const [queueError, setQueueError] = useState<ApiError | null>(null);

  const centralSource = useRef<'list' | 'lookup'>('list');
  const [sourceShown, setSourceShown] = useState<'list' | 'lookup'>('list');
  const [centralRows, setCentralRows] = useState<CentralRow[]>([]);
  const [centralLoaded, setCentralLoaded] = useState(false);
  const [centralError, setCentralError] = useState<ApiError | null>(null);
  const [resendBusy, setResendBusy] = useState(false);
  const [resendMessage, setResendMessage] = useState<KioskMessage>(NO_MESSAGE);

  const refreshCentral = useCallback(async (queueResult: ApiResult<QueueSnapshot>) => {
    const events = queueResult.ok ? queueResult.data.events : [];
    if (centralSource.current === 'list') {
      const r = await api.listCentralEvents(CENTRAL_LIST_LIMIT);
      const list = r.ok ? parseCentralList(r.data) : null;
      if (list !== null) {
        setCentralRows(rowsFromList(list, events));
        setCentralError(null);
        setCentralLoaded(true);
        return;
      }
      if (!r.ok && !isMissingRoute(r.error)) {
        setCentralError(r.error);
        setCentralLoaded(true);
        return;
      }
      centralSource.current = 'lookup';
      setSourceShown('lookup');
    }
    if (!queueResult.ok) {
      setCentralRows([]);
      setCentralError(queueResult.error);
      setCentralLoaded(true);
      return;
    }
    const lookups = await Promise.all(
      events.map(async (e) => [e.attendanceId, presenceFrom(await api.getCentralEvent(e.attendanceId))] as const),
    );
    setCentralRows(joinWithQueue(events, new Map(lookups)));
    setCentralError(null);
    setCentralLoaded(true);
  }, []);

  const refreshKiosk = useCallback(async () => {
    const [q, s] = await Promise.all([api.getQueue(QUEUE_LIMIT), api.getSyncState()]);
    setQueue(q.ok ? q.data : null);
    setQueueError(q.ok ? null : q.error);
    setOffline(s.ok ? s.data.centralSyncDisabled : null);
    await refreshCentral(q);
  }, [refreshCentral]);

  usePolling(refreshKiosk, POLL_MS);

  const liveCode = (qr.phase === 'ready' || qr.phase === 'stale') && secondsLeft(qr.code, nowMs) > 0 ? qr.code : null;
  const selectedGate = DEMO_GATES.find((g) => g.id === gateId) ?? DEMO_GATES[0];

  const scan = async (code: QrCode | null = liveCode) => {
    if (code === null || scanning) return;
    setScanning(true);
    const started = performance.now();
    const r = await api.scan({ gateId: selectedGate.id, direction: selectedGate.direction, qrPayload: code.payload });
    const roundTripMs = Math.round(performance.now() - started);
    setScanning(false);
    setScanView(r.ok ? describeScan(parseScanResponse(r.data), roundTripMs) : describeScanError(r.error, roundTripMs));
    void refreshKiosk();
  };

  const kioskAction = async (kind: Exclude<KioskBusy, null>) => {
    setKioskBusy(kind);
    setKioskMessage(NO_MESSAGE);
    if (kind === 'offline' && offline !== null) {
      const r = await api.setOffline(!offline);
      if (r.ok) setOffline(r.data.centralSyncDisabled);
      setKioskMessage(r.ok ? NO_MESSAGE : { text: null, error: r.error });
    } else if (kind === 'sync') {
      const r = await api.runSync();
      setKioskMessage(r.ok ? { text: formatSyncReport(r.data), error: null } : { text: null, error: r.error });
    } else if (kind === 'whitelist') {
      const r = await api.syncWhitelist();
      setKioskMessage(r.ok ? { text: 'Đã đồng bộ whitelist từ user-qr-service.', error: null } : { text: null, error: r.error });
    } else if (kind === 'synthetic') {
      const r = await api.queueSyntheticEvent({ employeeId, gateId: selectedGate.id, direction: selectedGate.direction });
      setKioskMessage(
        r.ok
          ? { text: 'Đã thêm một sự kiện mẫu (không qua kiểm tra QR) vào hàng đợi.', error: null }
          : { text: null, error: r.error },
      );
    }
    setKioskBusy(null);
    void refreshKiosk();
  };

  const resendBody = queue ? buildResendBody(queue.events, KIOSK_ID) : null;

  const resend = async () => {
    if (resendBody === null) return;
    setResendBusy(true);
    const r = await api.resendToCentral(resendBody);
    setResendBusy(false);
    setResendMessage(r.ok ? { text: summarizeResend(r.data), error: null } : { text: null, error: r.error });
    void refreshKiosk();
  };

  return (
    <div className="tmc-demo">
      <header className="tmc-bar">
        <h1 className="tmc-brand">TMC Attendance</h1>
      </header>
      <main className="demo-page">
        <div className="demo-grid">
          <EmployeeColumn
            employees={DEMO_EMPLOYEES}
            selectedId={employeeId}
            onSelect={setEmployeeId}
            qr={qr}
            nowMs={nowMs}
            fetching={qrFetching}
            onRetry={() => void loadQr(employeeId)}
            action={action}
            onRotate={() => void runAction('rotate')}
            onRevoke={() => void runAction('revoke')}
            onActivate={() => void runAction('activate')}
            onScanRevoked={revokedCode && !scanning ? () => void scan(revokedCode) : undefined}
          />
          <KioskColumn
            gates={DEMO_GATES}
            gateId={gateId}
            onGate={setGateId}
            canScan={liveCode !== null}
            scanning={scanning}
            onScan={() => void scan()}
            scanView={scanView}
            offline={offline}
            onToggleOffline={() => void kioskAction('offline')}
            busy={kioskBusy}
            onSyncNow={() => void kioskAction('sync')}
            onSyncWhitelist={() => void kioskAction('whitelist')}
            onSynthetic={() => void kioskAction('synthetic')}
            message={kioskMessage}
            queue={queue}
            queueError={queueError}
          />
          <CentralColumn
            source={sourceShown}
            rows={centralRows}
            loaded={centralLoaded}
            error={centralError}
            resendBusy={resendBusy}
            canResend={resendBody !== null}
            onResend={() => void resend()}
            resendMessage={resendMessage}
          />
        </div>
      </main>
    </div>
  );
}
