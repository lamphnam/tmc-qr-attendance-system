import { describeApiError, type ApiError } from '../../api/client';

export function ErrorIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M10 6v5M10 14h.01" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

export default function ApiErrorNotice({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  const t = describeApiError(error);
  return (
    <div className="tmc-error" role="alert">
      <ErrorIcon />
      <p className="tmc-error-title">{t.title}</p>
      {t.detail && <p className="tmc-error-detail">{t.detail}</p>}
      {t.correlationId && (
        <p className="tmc-error-id">
          <span>Mã tham chiếu: </span>
          {t.correlationId}
        </p>
      )}
      {onRetry && (
        <div className="tmc-error-actions">
          <button type="button" className="tmc-btn tmc-btn-secondary tmc-btn-sm" onClick={onRetry}>
            Thử lại
          </button>
        </div>
      )}
    </div>
  );
}
