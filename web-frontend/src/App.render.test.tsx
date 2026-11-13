import { readFileSync } from 'node:fs';
import { renderToString } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import App from './App';
import { AREAS, SERVICES } from './catalog';

const render = (path: string) =>
  renderToString(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );

describe('every route renders without throwing', () => {
  it('status page lists every service and says it is a scaffold', () => {
    const html = render('/');
    expect(html).toContain('Development scaffold');
    for (const service of SERVICES) expect(html).toContain(service.name);
  });

  it.each(AREAS)('$path is labelled "Not implemented"', (area) => {
    const html = render(area.path);
    expect(html).toContain('Not implemented');
    expect(html).toContain(area.title);
  });

  it('the dev slice page is labelled DEV-ONLY and warns that SYNCED is not a gate status', () => {
    const html = render('/dev/slice');
    expect(html).toContain('DEV-ONLY');
    expect(html).toContain('not proof that a gate opened');
  });

  it('the main-flow demo is a page of its own: three columns, one h1, no scaffold topbar or nav', () => {
    const html = render('/demo');
    expect(html.match(/<h1\b/g)).toHaveLength(1);
    for (const heading of ['Nhân viên: mã QR động', 'Kiosk BD-K01 tại cổng', 'Trung tâm: PostgreSQL']) expect(html).toContain(heading);
    expect(html).toContain('Đang lấy mã…');
    expect(html).not.toContain('Development scaffold');
    expect(html).not.toContain('class="topbar"');
    expect(html).not.toContain('Kiosk simulator');
    expect(html.match(/<main\b/g)).toHaveLength(1);
    expect(html).toContain('<main class="demo-page">');
  });

  it('every other page keeps the scaffold shell, which links to the demo', () => {
    for (const path of ['/', '/dev/slice', '/nope']) {
      const html = render(path);
      expect(html, path).toContain('class="topbar"');
      expect(html, path).toContain('href="/demo"');
      expect(html, path).not.toContain('tmc-demo');
    }
  });

  it('unknown paths do not crash', () => {
    expect(render('/nope')).toContain('Unknown page');
  });
});

describe('catalog stays consistent with the gateway', () => {
  const nginx = readFileSync(new URL('../../nginx-gateway/nginx.conf', import.meta.url), 'utf8');

  it('every health path the dashboard polls exists in nginx.conf', () => {
    for (const service of SERVICES) expect(nginx, service.healthPath).toContain(`location = ${service.healthPath}`);
  });

  it('service ids are unique', () => {
    expect(new Set(SERVICES.map((s) => s.id)).size).toBe(SERVICES.length);
  });
});
