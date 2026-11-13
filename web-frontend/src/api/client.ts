export type ApiError =
  | { kind: 'NOT_IMPLEMENTED'; status: 501; message: string | null; correlationId: string | null }
  | { kind: 'HTTP'; status: number; code: string | null; message: string | null; correlationId: string | null }
  | { kind: 'NETWORK'; message: string };

export type ApiResult<T> = { ok: true; status: number; data: T } | { ok: false; error: ApiError };

export interface ApiRequest {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  timeoutMs?: number;
}

const DEFAULT_TIMEOUT_MS = 8000;

function text(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function parseJson(raw: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}

export function interpretError(status: number, body: unknown, requestId: string | null): ApiError {
  const record = (typeof body === 'object' && body !== null ? body : {}) as Record<string, unknown>;
  const correlationId = text(record.correlationId) ?? requestId;
  if (status === 501) return { kind: 'NOT_IMPLEMENTED', status: 501, message: text(record.message), correlationId };
  return { kind: 'HTTP', status, code: text(record.code), message: text(record.message), correlationId };
}

export async function apiFetch<T>(
  path: string,
  request: ApiRequest = {},
  fetchImpl: typeof fetch = fetch,
): Promise<ApiResult<T>> {
  const { method = 'GET', body, timeoutMs = DEFAULT_TIMEOUT_MS } = request;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const raw = await response.text();
    const parsed = raw === '' ? undefined : parseJson(raw);
    const requestId = response.headers.get('X-Request-Id');
    if (!response.ok) return { ok: false, error: interpretError(response.status, parsed, requestId) };
    if (raw === '') return { ok: true, status: response.status, data: null as T };
    if (parsed === undefined) {
      return {
        ok: false,
        error: {
          kind: 'HTTP',
          status: response.status,
          code: 'INVALID_RESPONSE_BODY',
          message: 'the server answered 2xx without JSON',
          correlationId: requestId,
        },
      };
    }
    return { ok: true, status: response.status, data: parsed as T };
  } catch (e) {
    const message = controller.signal.aborted ? `timeout after ${timeoutMs} ms` : e instanceof Error ? e.message : String(e);
    return { ok: false, error: { kind: 'NETWORK', message } };
  } finally {
    clearTimeout(timer);
  }
}

export function isMissingRoute(error: ApiError): boolean {
  return error.kind === 'NOT_IMPLEMENTED' || (error.kind === 'HTTP' && (error.status === 404 || error.status === 405));
}

export interface ErrorText {
  title: string;
  detail: string | null;
  correlationId: string | null;
}

export function describeApiError(error: ApiError): ErrorText {
  if (error.kind === 'NETWORK') return { title: 'Không kết nối được máy chủ', detail: error.message, correlationId: null };
  if (error.kind === 'NOT_IMPLEMENTED') {
    return { title: 'Chưa triển khai ở backend (HTTP 501)', detail: error.message, correlationId: error.correlationId };
  }
  if (error.status === 404 || error.status === 405) {
    return {
      title: `Backend chưa có route này (HTTP ${error.status})`,
      detail: error.message,
      correlationId: error.correlationId,
    };
  }
  const code = error.code ? ` ${error.code}` : '';
  return { title: `Yêu cầu thất bại (HTTP ${error.status}${code})`, detail: error.message, correlationId: error.correlationId };
}
