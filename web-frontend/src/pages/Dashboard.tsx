import { useCallback, useEffect, useState } from 'react';
import { SERVICES } from '../catalog';
import { fetchHealth, type HealthResult } from '../health';

const REFRESH_MS = 10_000;

export default function Dashboard() {
  const [results, setResults] = useState<Record<string, HealthResult>>({});
  const [checkedAt, setCheckedAt] = useState<Date | null>(null);

  const refresh = useCallback(async () => {
    const entries = await Promise.all(SERVICES.map(async (s) => [s.id, await fetchHealth(s.healthPath)] as const));
    setResults(Object.fromEntries(entries));
    setCheckedAt(new Date());
  }, []);

  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), REFRESH_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  return (
    <section>
      <h1>Service status</h1>
      <p className="notice">
        Development scaffold. The <em>Live health</em> column is fetched from each service&apos;s real endpoint through
        the gateway. <em>Feature status</em> is declared (see docs/STATUS.md); SCAFFOLD and DEV_SLICE mean the
        business features are not built yet. There is no authentication anywhere in this stack.
      </p>
      <p>
        <button onClick={() => void refresh()}>Refresh now</button>{' '}
        <span className="muted">{checkedAt ? `checked ${checkedAt.toLocaleTimeString()}` : 'checking...'}</span>
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Service</th>
              <th>Owner</th>
              <th>Live health</th>
              <th>Components</th>
              <th>Feature status</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            {SERVICES.map((service) => {
              const result = results[service.id];
              return (
                <tr key={service.id}>
                  <td data-label="Service">
                    <code>{service.name}</code>
                  </td>
                  <td data-label="Owner">{service.owner}</td>
                  <td data-label="Live health">
                    {result ? (
                      <span className={`badge ${result.state === 'UP' ? 'badge-ok' : 'badge-bad'}`}>{result.state}</span>
                    ) : (
                      <span className="badge">...</span>
                    )}
                  </td>
                  <td data-label="Components">
                    {result && Object.keys(result.components).length > 0
                      ? Object.entries(result.components)
                          .filter(([name]) => name !== 'ping' && name !== 'diskSpace')
                          .map(([name, status]) => `${name}: ${status}`)
                          .join(', ') || '-'
                      : '-'}
                  </td>
                  <td data-label="Feature status">
                    <span className={`badge badge-${service.status.toLowerCase()}`}>{service.status}</span>
                  </td>
                  <td data-label="Note">{service.note}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
