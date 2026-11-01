/**
 * What each runtime is DECLARED to be. This mirrors docs/STATUS.md and is hand-maintained: when a
 * module's real status changes, update both. The live health column next to it is fetched, never declared.
 */
export type FeatureStatus = 'READY' | 'DEV_SLICE' | 'SCAFFOLD' | 'MOCK';

export interface ServiceDef {
  id: string;
  name: string;
  owner: string;
  healthPath: string;
  status: FeatureStatus;
  note: string;
}

export const SERVICES: ServiceDef[] = [
  { id: 'gateway', name: 'nginx-gateway', owner: 'Nam / Khoa', healthPath: '/gateway/health', status: 'READY', note: 'Routing only. Plain HTTP, no TLS, no auth (dev).' },
  { id: 'user-qr', name: 'user-qr-service', owner: 'Khoa', healthPath: '/services/user-qr/health', status: 'SCAFFOLD', note: 'Auth, RBAC, TOTP, whitelist: routes answer 501.' },
  { id: 'gate-station', name: 'gate-station-service', owner: 'Khôi', healthPath: '/services/gate-station/health', status: 'SCAFFOLD', note: 'Registration, heartbeat, manual open: routes answer 501.' },
  { id: 'attendance', name: 'attendance-processor', owner: 'Khôi', healthPath: '/services/attendance/health', status: 'DEV_SLICE', note: 'Idempotent ingestion works via dev-only /api/dev/attendance/*. Production /api/v1 routes answer 501.' },
  { id: 'reporting', name: 'reporting-service', owner: 'Nam', healthPath: '/services/reporting/health', status: 'SCAFFOLD', note: 'Reports, dashboard, HR export: routes answer 501.' },
  { id: 'kiosk', name: 'kiosk-agent', owner: 'Khánh', healthPath: '/services/kiosk/health', status: 'DEV_SLICE', note: 'SQLite queue + sync works with synthetic events. QR scan, TOTP, whitelist: 501.' },
  { id: 'mock-turnstile', name: 'mock-turnstile-api', owner: 'Khánh', healthPath: '/services/mock-turnstile/health', status: 'MOCK', note: 'Dev mock. Never proves a physical gate opened.' },
  { id: 'mock-hr', name: 'mock-hr-payroll-api', owner: 'Nam', healthPath: '/services/mock-hr/health', status: 'MOCK', note: 'Dev mock with idempotent upsert and failure injection.' },
];

export interface AreaDef {
  path: string;
  title: string;
  owner: string;
  requirements: string;
  needs: string[];
}

/** Placeholder areas. Every one renders "Not implemented" until its owner builds it. */
export const AREAS: AreaDef[] = [
  { path: '/employee', title: 'Employee', owner: 'Khiêm + Khoa', requirements: 'FR-1.3, FR-3.2', needs: ['login + JWT (user-qr-service)', 'GET /api/v1/qr/generate with a refresh at each 30 s step', 'attendance history (reporting-service)'] },
  { path: '/kiosk', title: 'Kiosk simulator', owner: 'Khiêm + Khánh', requirements: 'FR-3.1', needs: ['POST /api/local/scan on the kiosk-agent (501 today)', 'an offline toggle wired to the kiosk', 'scan result view that separates ALLOWED from gate-command status'] },
  { path: '/guard', title: 'Security guard', owner: 'Khiêm + Khôi', requirements: 'FR-2.2', needs: ['Guard role (user-qr-service)', 'POST /api/v1/gates/{id}/manual-open with a mandatory reason (gate-station-service)'] },
  { path: '/hr', title: 'HR', owner: 'Khiêm + Nam', requirements: 'FR-1.4, FR-4.1, ADR-03', needs: ['QR revoke (user-qr-service)', 'reports and HR export trigger (reporting-service)'] },
  { path: '/admin', title: 'System admin', owner: 'Khiêm + Khoa', requirements: 'FR-5.1, FR-5.2, NFR-8', needs: ['user and role management', 'audit log viewer with integrity check'] },
];
