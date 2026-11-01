#!/usr/bin/env node
// Static checks for the repository's markdown. No dependencies (Node >= 20).
//
//   node scripts/check-docs.mjs [--root <dir>]
//
// FAILS (exit 1) on:
//   * a relative markdown link whose file does not exist, or whose #anchor is not a heading of the target (GitHub slugs)
//   * a reference to a doc name that was merged away in the 2026-11-01 cleanup (outside the "Old names" table)
//   * a docs/*.md file that docs/README.md does not link to (the document map must stay complete)
// WARNS (exit 0) on: the same long line appearing in two or more markdown files (a fact stated twice).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const argRoot = process.argv.includes('--root') ? process.argv[process.argv.indexOf('--root') + 1] : null;
const ROOT = path.resolve(argRoot ?? fileURLToPath(new URL('..', import.meta.url)));
const SKIP_DIRS = new Set(['node_modules', 'target', 'dist', '.git', '.verify-logs']);
const STALE_NAMES = /AGENT-RULES|BOOTSTRAP-STATUS|TEAM-TODO|docs\/(0\d|1[0-5])\b|\b(0\d|1[0-5])-(SOURCE|PROJECT|M2|ARCHITECTURE|REPOSITORY|OWNERSHIP|API|DATA|RUNTIME|BOOTSTRAP|TESTING|RISKS|ACCEPTANCE|TRACEABILITY|JAVA|TEAM)/;
const MIN_DUPLICATE_LENGTH = 60;

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(path.join(dir, entry.name), out);
    } else if (entry.name.endsWith('.md')) {
      out.push(path.join(dir, entry.name));
    }
  }
  return out;
}

const slug = (heading) =>
  heading.replace(/`/g, '').trim().toLowerCase().replace(/[^\p{L}\p{N}_\- ]/gu, '').replace(/ /g, '-');

/** Lines of a markdown file outside fenced code blocks, with their 1-based numbers. */
function proseLines(file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const out = [];
  let fenced = false;
  lines.forEach((text, index) => {
    if (/^\s*```/.test(text)) {
      fenced = !fenced;
    } else if (!fenced) {
      out.push({ no: index + 1, text });
    }
  });
  return out;
}

const headingCache = new Map();
function headingsOf(file) {
  if (!headingCache.has(file)) {
    const seen = new Map();
    const set = new Set();
    for (const { text } of proseLines(file)) {
      const m = /^#{1,6}\s+(.*?)\s*#*\s*$/.exec(text);
      if (!m) continue;
      const base = slug(m[1]);
      const n = seen.get(base) ?? 0;
      seen.set(base, n + 1);
      set.add(n === 0 ? base : `${base}-${n}`);
    }
    headingCache.set(file, set);
  }
  return headingCache.get(file);
}

const rel = (file) => path.relative(ROOT, file).split(path.sep).join('/');
const files = walk(ROOT).sort();
const errors = [];
const duplicates = new Map();
let linksChecked = 0;

for (const file of files) {
  let inOldNames = false;
  for (const { no, text } of proseLines(file)) {
    if (rel(file) === 'docs/README.md' && /^##\s+Old names/.test(text)) inOldNames = true;

    const withoutCode = text.replace(/`[^`]*`/g, '');
    for (const m of withoutCode.matchAll(/(?<!!)\[[^\]]*\]\(([^)\s]+)\)/g)) {
      const target = m[1];
      if (/^[a-z][a-z0-9+.-]*:/i.test(target)) continue;
      linksChecked += 1;
      const [filePart, fragment] = target.split('#');
      const dest = filePart ? path.normalize(path.join(path.dirname(file), filePart)) : file;
      if (!fs.existsSync(dest)) {
        errors.push(`${rel(file)}:${no}: link to missing file "${target}"`);
      } else if (fragment && dest.endsWith('.md') && !headingsOf(dest).has(fragment)) {
        errors.push(`${rel(file)}:${no}: link to missing anchor "#${fragment}" in ${rel(dest)}`);
      }
    }

    if (!inOldNames && STALE_NAMES.test(withoutCode)) {
      errors.push(`${rel(file)}:${no}: mentions a document name that was merged away (see docs/README.md "Old names")`);
    }

    const normalized = text.replace(/\s+/g, ' ').trim().replace(/^\||\|$/g, '').trim();
    if (normalized.length > MIN_DUPLICATE_LENGTH && !/^(#|-{3,}|\|\s*-)/.test(normalized)) {
      if (!duplicates.has(normalized)) duplicates.set(normalized, new Set());
      duplicates.get(normalized).add(rel(file));
    }
  }
}

const docsReadme = path.join(ROOT, 'docs', 'README.md');
if (fs.existsSync(docsReadme)) {
  const readme = fs.readFileSync(docsReadme, 'utf8');
  for (const name of fs.readdirSync(path.join(ROOT, 'docs')).filter((n) => n.endsWith('.md') && n !== 'README.md')) {
    if (!readme.includes(`](${name})`)) errors.push(`docs/README.md: does not link to docs/${name} (document map incomplete)`);
  }
}

const warnings = [...duplicates].filter(([, where]) => where.size > 1);
console.log(`${files.length} markdown files, ${linksChecked} relative links checked`);
for (const [line, where] of warnings) {
  console.log(`WARN  same line in ${[...where].join(', ')}: ${line.slice(0, 100)}${line.length > 100 ? '…' : ''}`);
}
for (const error of errors) console.log(`FAIL  ${error}`);
console.log(errors.length === 0 ? `OK (${warnings.length} duplicate-line warning(s))` : `${errors.length} problem(s)`);
process.exit(errors.length === 0 ? 0 : 1);
