import type { Direction } from '../api/types';

export const DEMO_PATH = '/demo';
export const KIOSK_ID = 'BD-K01';

export interface DemoEmployee {
  id: string;
  name: string;
  note: string;
}

export const DEMO_EMPLOYEES: readonly DemoEmployee[] = [
  { id: 'EMP001', name: 'Demo Employee One', note: 'BD · Assembly' },
  { id: 'EMP002', name: 'Demo Employee Two', note: 'BD · Packaging' },
  { id: 'EMP003', name: 'Demo Employee Three', note: 'LA · Assembly' },
  { id: 'EMP004', name: 'Demo Revoked Person', note: 'BD · REVOKED trong seed' },
];

export interface DemoGate {
  id: string;
  direction: Direction;
  label: string;
}

export const DEMO_GATES: readonly DemoGate[] = [
  { id: 'BD-G01', direction: 'IN', label: 'BD-G01 · VÀO' },
  { id: 'BD-G02', direction: 'OUT', label: 'BD-G02 · RA' },
];
