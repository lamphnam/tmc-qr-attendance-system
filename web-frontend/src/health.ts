/** Live health of one service, derived only from what its real endpoint answered. Nothing here is hard-coded UP. */
export type HealthState = 'UP' | 'DOWN' | 'UNREACHABLE';

export interface HealthResult {
  state: HealthState;
  /** Spring Actuator components, e.g. { db: 'UP', sqliteQueue: 'UP' }. Empty for services that report none. */
  components: Record<string, string>;
  httpStatus: number | null;
}

/**
 * UP only when the endpoint answered HTTP 200 AND its JSON says status UP.
 * A reachable service that says DOWN (Spring answers 503 + JSON) is DOWN; no usable answer is UNREACHABLE.
 */
export function interpretHealth(httpStatus: number | null, body: unknown): HealthResult {
  if (httpStatus === null || typeof body !== 'object' || body === null) {
    return { state: 'UNREACHABLE', components: {}, httpStatus };
  }
  const record = body as { status?: unknown; components?: unknown };
  if (typeof record.status !== 'string') {
    return { state: 'UNREACHABLE', components: {}, httpStatus };
  }

  const components: Record<string, string> = {};
  if (typeof record.components === 'object' && record.components !== null) {
    for (const [name, value] of Object.entries(record.components as Record<string, unknown>)) {
      const status = (value as { status?: unknown } | null)?.status;
      if (typeof status === 'string') components[name] = status;
    }
  }
  const up = httpStatus === 200 && record.status === 'UP';
  return { state: up ? 'UP' : 'DOWN', components, httpStatus };
}

export async function fetchHealth(path: string, fetchImpl: typeof fetch = fetch, timeoutMs = 4000): Promise<HealthResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(path, { signal: controller.signal, headers: { Accept: 'application/json' } });
    let body: unknown = null;
    try {
      body = await response.json();
    } catch {
      body = null; // the gateway answers 502 with JSON, but a proxy in between may answer HTML
    }
    return interpretHealth(response.status, body);
  } catch {
    return interpretHealth(null, null);
  } finally {
    clearTimeout(timer);
  }
}
