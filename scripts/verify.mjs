#!/usr/bin/env node
// One command that runs the repository's whole verification and reports it HONESTLY (exit code, counts, skipped).
// Cross-platform, no dependencies (Node >= 20).
//
//   node scripts/verify.mjs [--fast] [--no-integration] [--down] [--only <stage>[,<stage>]]
//
//   --fast            unit/static stages only; never touches Docker or the running stack
//   --no-integration  skip tests/integration (it stops/restarts some containers)
//   --down            run `docker compose down` after the stack stages (data volumes are kept)
//   --only a,b        run just those stages: java, frontend, mocks, docs, compose-config, stack-up, smoke, integration
//
// A stage that did not run is reported SKIPPED, never PASS. Full logs: .verify-logs/<stage>.log (git-ignored).
// Maven needs a JDK 21: if `mvn -v` is not on 21 this script looks for one (JDK21_HOME, then common install folders).
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const LOGS = path.join(ROOT, '.verify-logs');
const WIN = process.platform === 'win32';
const args = new Set(process.argv.slice(2));
const only = process.argv.includes('--only') ? process.argv[process.argv.indexOf('--only') + 1].split(',') : null;
const FAST = args.has('--fast');

fs.mkdirSync(LOGS, { recursive: true });
const results = [];

function run(command, commandArgs, { cwd = ROOT, env = process.env } = {}) {
  const started = Date.now();
  // On Windows the command runs through a shell (needed for mvn.cmd / npm.cmd), which breaks on paths with spaces.
  const program = WIN && /\s/.test(command) ? `"${command}"` : command;
  const r = spawnSync(program, commandArgs, { cwd, env, encoding: 'utf8', shell: WIN, maxBuffer: 256 * 1024 * 1024 });
  return { status: r.status ?? 1, output: `${r.stdout ?? ''}${r.stderr ?? ''}${r.error ? `\n${r.error.message}` : ''}`, seconds: (Date.now() - started) / 1000 };
}

function record(stage, state, detail, seconds = 0, output = '') {
  if (output) fs.writeFileSync(path.join(LOGS, `${stage}.log`), output);
  results.push({ stage, state, detail, seconds });
  console.log(`${state.padEnd(7)} ${stage}  ${detail}${seconds ? `  (${seconds.toFixed(1)}s)` : ''}`);
  if (state === 'FAIL' && output) console.log(output.trim().split('\n').slice(-12).map((l) => `        | ${l}`).join('\n'));
}

const wanted = (stage) => !only || only.includes(stage);
function stage(name, { stack = false, body }) {
  if (!wanted(name)) return;
  if (stack && FAST) return record(name, 'SKIPPED', '--fast');
  if (name === 'integration' && args.has('--no-integration')) return record(name, 'SKIPPED', '--no-integration');
  body(name);
}

function simple(name, command, commandArgs, options, summarise = () => '') {
  const r = run(command, commandArgs, options);
  record(name, r.status === 0 ? 'PASS' : 'FAIL', r.status === 0 ? summarise(r.output) || `exit 0` : `exit ${r.status}`, r.seconds, r.output);
  return r.status === 0;
}

// ---------------------------------------------------------------------------------------------------------- JDK
function jdk21Env() {
  const versionOf = (env) => /Java version: (\d+)/.exec(run('mvn', ['-v'], { env }).output)?.[1];
  if (versionOf(process.env) === '21') return { env: process.env, note: 'JAVA_HOME already on 21' };

  const roots = WIN
    ? ['Java', 'Eclipse Adoptium', 'Microsoft', 'Amazon Corretto', 'Zulu', 'BellSoft'].map((d) => path.join(process.env.ProgramFiles ?? 'C:\\Program Files', d))
    : ['/usr/lib/jvm', '/Library/Java/JavaVirtualMachines', `${process.env.HOME}/.sdkman/candidates/java`];
  const candidates = [process.env.JDK21_HOME];
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;
    for (const entry of fs.readdirSync(root)) {
      if (!/21/.test(entry)) continue;
      const home = path.join(root, entry);
      candidates.push(fs.existsSync(path.join(home, 'Contents', 'Home')) ? path.join(home, 'Contents', 'Home') : home);
    }
  }
  for (const home of candidates.filter(Boolean)) {
    if (!fs.existsSync(path.join(home, 'bin', WIN ? 'java.exe' : 'java'))) continue;
    const env = { ...process.env, JAVA_HOME: home, PATH: `${path.join(home, 'bin')}${path.delimiter}${process.env.PATH}` };
    if (versionOf(env) === '21') return { env, note: `JAVA_HOME overridden to ${home}` };
  }
  return null;
}

// ---------------------------------------------------------------------------------------------------------- stages
stage('java', {
  body: (name) => {
    const jdk = jdk21Env();
    if (!jdk) return record(name, 'FAIL', 'Maven is not on JDK 21 and none was found. Set JDK21_HOME to a JDK 21 directory.');
    const r = run('mvn', ['-B', '-ntp', 'test'], { env: jdk.env });
    const sums = [...r.output.matchAll(/^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$/gm)].map((m) => m.slice(1).map(Number));
    const total = sums.reduce((a, s) => a.map((v, i) => v + s[i]), [0, 0, 0, 0]);
    const detail = `${total[0]} tests, ${total[1]} failed, ${total[2]} errors, ${total[3]} SKIPPED (${jdk.note})`;
    // A skipped test is not a pass: Testcontainers tests are skipped when Docker is unavailable.
    record(name, r.status === 0 ? (total[3] > 0 ? 'WARN' : 'PASS') : 'FAIL', r.status === 0 ? detail : `exit ${r.status}; ${detail}`, r.seconds, r.output);
  },
});

stage('frontend', {
  body: (name) => {
    const cwd = path.join(ROOT, 'web-frontend');
    const steps = [];
    if (!fs.existsSync(path.join(cwd, 'node_modules'))) steps.push(['npm', ['ci', '--no-audit', '--no-fund']]);
    steps.push(['npm', ['run', 'typecheck']], ['npm', ['test']], ['npm', ['run', 'build']]);
    let seconds = 0;
    let log = '';
    for (const [cmd, a] of steps) {
      const r = run(cmd, a, { cwd });
      seconds += r.seconds;
      log += `$ ${cmd} ${a.join(' ')}\n${r.output}\n`;
      if (r.status !== 0) return record(name, 'FAIL', `npm ${a.join(' ')} exited ${r.status}`, seconds, log);
    }
    record(name, 'PASS', `typecheck + test + build; ${/Tests\s+(\d+) passed/.exec(log)?.[1] ?? '?'} tests passed`, seconds, log);
  },
});

stage('mocks', {
  body: (name) => {
    let seconds = 0;
    let log = '';
    const counts = [];
    for (const mock of ['mock-turnstile-api', 'mock-hr-payroll-api']) {
      const r = run('npm', ['test'], { cwd: path.join(ROOT, 'mock-external-systems', mock) });
      seconds += r.seconds;
      log += `$ ${mock}\n${r.output}\n`;
      if (r.status !== 0) return record(name, 'FAIL', `${mock} exited ${r.status}`, seconds, log);
      counts.push(`${mock}: ${/ℹ pass (\d+)/.exec(r.output)?.[1] ?? '?'}`);
    }
    record(name, 'PASS', counts.join(', '), seconds, log);
  },
});

stage('docs', { body: (name) => simple(name, process.execPath, ['scripts/check-docs.mjs'], {}, (o) => o.trim().split('\n').pop()) });
stage('compose-config', { body: (name) => simple(name, 'docker', ['compose', 'config', '--quiet']) });

stage('stack-up', {
  stack: true,
  body: (name) => simple(name, 'docker', ['compose', 'up', '--build', '-d', '--wait'], {}, () => 'all healthchecks passed'),
});

stage('smoke', {
  stack: true,
  body: (name) => {
    const [cmd, a] = WIN
      ? ['powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts\\smoke.ps1']]
      : ['bash', ['scripts/smoke.sh']];
    simple(name, cmd, a, {}, (o) => /SMOKE PASSED.*/.exec(o)?.[0] ?? '');
  },
});

stage('integration', {
  stack: true,
  body: (name) => simple(name, process.execPath, ['--test', 'tests/integration/slice.test.mjs'], {}, (o) => `${/ℹ pass (\d+)/.exec(o)?.[1] ?? '?'} of ${/ℹ tests (\d+)/.exec(o)?.[1] ?? '?'} passed`),
});

if (args.has('--down') && !FAST) {
  const r = run('docker', ['compose', 'down']);
  console.log(`down    docker compose down exited ${r.status} (volumes kept; use \`docker compose down -v\` to wipe data)`);
}

// ---------------------------------------------------------------------------------------------------------- summary
console.log('\n| Stage | Result | Detail |\n|---|---|---|');
for (const r of results) console.log(`| ${r.stage} | ${r.state} | ${r.detail} |`);
const failed = results.filter((r) => r.state === 'FAIL');
const notRun = results.filter((r) => r.state === 'SKIPPED' || r.state === 'WARN');
const partial = only ? ` — PARTIAL RUN (--only ${only.join(',')}): stages not listed were not run` : '';
console.log(`\n${failed.length === 0 ? 'ALL RUN STAGES PASSED' : `${failed.length} STAGE(S) FAILED`}${notRun.length ? ` — ${notRun.length} stage(s) skipped or with skipped tests: this is NOT a full pass` : ''}${partial}`);
process.exit(failed.length === 0 ? 0 : 1);
