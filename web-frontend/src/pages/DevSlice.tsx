import { useCallback, useEffect, useState } from 'react';

interface QueuedEvent {
  attendanceId: string;
  employeeId: string;
  gateId: string;
  direction: string;
  syncStatus: string;
  gateCommandStatus: string;
  retryCount: number;
  lastError: string | null;
}

interface Snapshot {
  counts: { pending: number; synced: number; failed: number };
  events: QueuedEvent[];
}

const BASE = '/api/dev/kiosk';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
  });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  return (await response.json()) as T;
}

/** DEV-ONLY view of the P1 vertical slice (kiosk SQLite queue -> central PostgreSQL). Not the kiosk simulator. */
export default function DevSlice() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [offline, setOffline] = useState<boolean | null>(null);
  const [message, setMessage] = useState<string>('');

  const load = useCallback(async () => {
    try {
      const [queue, state] = await Promise.all([
        call<Snapshot>('/queue?limit=15'),
        call<{ centralSyncDisabled: boolean }>('/sync/state'),
      ]);
      setSnapshot(queue);
      setOffline(state.centralSyncDisabled);
    } catch (e) {
      setSnapshot(null);
      setMessage(`kiosk dev endpoints unreachable (${String(e)}). Is the stack up with the dev profile?`);
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), 3000);
    return () => clearInterval(timer);
  }, [load]);

  const act = async (label: string, run: () => Promise<unknown>) => {
    try {
      await run();
      setMessage(`${label}: ok`);
    } catch (e) {
      setMessage(`${label}: failed (${String(e)})`);
    }
    await load();
  };

  return (
    <section>
      <h1>
        Dev slice <span className="badge badge-warn">DEV-ONLY</span>
      </h1>
      <p className="notice">
        Synthetic events only: there is no QR, TOTP or whitelist check. The kiosk commits the event to SQLite as PENDING,
        then sends the open command to the mock turnstile; a sync pass sends it to central, which answers after its
        PostgreSQL commit. <strong>SYNCED is the central sync state, not proof that a gate opened.</strong>
      </p>
      <p className="actions">
        <button onClick={() => void act('queue event', () => call('/synthetic-event', { method: 'POST', body: '{}' }))}>
          Queue synthetic event
        </button>
        <button onClick={() => void act('sync', () => call('/sync/run', { method: 'POST' }))}>Run sync now</button>
        <button
          onClick={() =>
            void act('toggle offline', () =>
              call('/sync/offline', { method: 'PUT', body: JSON.stringify({ disabled: !offline }) }),
            )
          }
        >
          {offline ? 'Go online (resume central sync)' : 'Go offline (stop central sync only)'}
        </button>
      </p>
      <p>
        Central sync:{' '}
        <span className={`badge ${offline ? 'badge-bad' : 'badge-ok'}`}>
          {offline === null ? '...' : offline ? 'OFFLINE (simulated)' : 'online'}
        </span>
        {snapshot && (
          <span className="muted">
            {' '}
            &nbsp;pending {snapshot.counts.pending} | synced {snapshot.counts.synced} | failed {snapshot.counts.failed}
          </span>
        )}
      </p>
      {message && <p className="muted">{message}</p>}
      {snapshot && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>attendanceId</th>
                <th>Employee</th>
                <th>Gate</th>
                <th>Central sync</th>
                <th>Gate command</th>
                <th>Retries</th>
                <th>Last error</th>
              </tr>
            </thead>
            <tbody>
              {snapshot.events.map((e) => (
                <tr key={e.attendanceId}>
                  <td data-label="attendanceId">
                    <code>{e.attendanceId.slice(0, 8)}</code>
                  </td>
                  <td data-label="Employee">{e.employeeId}</td>
                  <td data-label="Gate">
                    {e.gateId} {e.direction}
                  </td>
                  <td data-label="Central sync">
                    <span className={`badge ${e.syncStatus === 'SYNCED' ? 'badge-ok' : e.syncStatus === 'FAILED' ? 'badge-bad' : 'badge-warn'}`}>
                      {e.syncStatus}
                    </span>
                  </td>
                  <td data-label="Gate command">{e.gateCommandStatus}</td>
                  <td data-label="Retries">{e.retryCount}</td>
                  <td data-label="Last error">{e.lastError ?? '-'}</td>
                </tr>
              ))}
              {snapshot.events.length === 0 && (
                <tr>
                  <td colSpan={7}>No events yet.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
